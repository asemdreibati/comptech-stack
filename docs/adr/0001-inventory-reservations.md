# ADR 0001: Inventory reservations that cannot oversell

- Status: Accepted
- Date: 2026-10-01
- Service: `inventory-service`

## Context

Checkout must hold stock for an order until it is paid, cancelled or abandoned. Selling a unit
twice costs real money and a customer, so correctness is not negotiable. Flash sales make it
hard: thousands of buyers hit the same SKU in the same second, and nearly all of them will be
turned away.

Requirements:

1. **Never oversell**, under any concurrency, retry pattern or partial failure.
2. **Idempotent**: a client retrying after a timeout must not reserve twice.
3. **Multi-line orders are all-or-nothing.**
4. **Abandoned checkouts give their stock back** automatically.
5. **Every state change reaches Kafka** (search availability, analytics, order service) without
   losing events or publishing ones that were rolled back.
6. **Throughput on one hot SKU** high enough to sell out a flash sale in seconds.

## Decision

### Source of truth: MongoDB with conditional updates in transactions

Each SKU is one document `{_id: sku, available, reserved}`. A reservation decrements
`available` with a guarded update, `updateOne({_id: sku, available: {$gte: qty}}, {$inc: ...})`,
inside a multi-document transaction that also inserts the reservation and its outbox event.
If any line's guard fails, the whole transaction aborts (requirement 3). Because the guard and
the decrement are a single atomic operation, nothing above this layer can cause an oversell.

**Idempotency** comes from a unique index on `reservations.orderId`. The reservation is inserted
*before* stock is touched, so a duplicate order fails before it changes anything. Replaying the
same order and lines returns the original reservation (`200`, `Idempotent-Replayed: true`).
Reusing the order ID with different lines is rejected (`409 IDEMPOTENCY_KEY_REUSED`).

**State machine**: `PENDING → CONFIRMED | RELEASED | EXPIRED`. Every transition is a conditional
`findAndModify` on the current status, so concurrent confirm/release/expire calls cannot both
succeed. Confirming checks `expiresAt` too, so a late payment cannot revive an expired hold.

**Expiry**: a sweeper on every instance finds `PENDING` reservations past `expiresAt` and expires
each with the same conditional transition. Exactly one instance wins each reservation, so no
leader election is needed.

### Events: transactional outbox

Events are written to an `outbox` collection in the same transaction as the state change
(requirement 5). A relay claims events with a time-limited lease (`findAndModify` on
`lockedUntil`), publishes them with `acks=all` and idempotent producers, keyed by reservation ID,
then marks them published. Delivery is at least once; consumers deduplicate on `eventId`. A TTL
index deletes published events after seven days.

### Throughput: three layers in front of the transaction

The first load test met requirement 1 but failed requirement 6: **about 45 reservations/s on a
hot SKU, and 7% of requests timed out.** Two separate problems caused that:

1. **Write conflicts.** MongoDB aborts a transaction that writes a document another open
   transaction has already written. With 64 threads on one SKU, optimistic retries kept
   colliding until they gave up.
2. **One journal flush per commit.** Each commit waits for the journal to sync to disk, so
   committing reservations one at a time caps a SKU at the disk's sync rate.

The fixes, each measured:

| Layer | What it does | Why it is safe |
|---|---|---|
| **Flash-sale gate** (Redis Lua) | When a sale is armed, each order line must claim a token. Once tokens run out, requests are rejected in Redis without touching MongoDB. | It never decides a sale; MongoDB's guard still does. Losing Redis means more MongoDB load, not overselling, so the gate **fails open**. |
| **Fast sold-out check** | A plain read rejects requests for SKUs that are visibly sold out, before any lock or transaction. | A stale read can only let a doomed request through to the real check. |
| **Per-SKU lock striping** | 1,024 striped locks queue writers per SKU on each instance, so their transactions stop aborting each other. A wait over 2 s sheds the request with `503 CONTENTION` + `Retry-After`. | Locks are taken in stripe order, so multi-SKU orders cannot deadlock. Instances still conflict with each other; `MongoTransactions` retries those with jittered backoff. |
| **Group commit (flat combining)** | Single-line requests join their stripe's queue. Whichever thread holds the lock drains the queue and commits **all** waiting requests in one transaction: one guarded `$inc` per SKU, a bulk insert of reservations and a bulk insert of outbox events. Each waiter then gets its own outcome. | Allocation is first come, first served within the batch, and still guarded by `available >= total`. A duplicate order ID makes the batch fall back to the one-at-a-time path, which resolves it as a replay. |

Gate details worth knowing:

- Keys use a `{sku}` hash tag (`inv:gate:{SKU}:tokens`, `inv:gate:{SKU}:claim:ORDER`), so they work
  on Redis Cluster.
- A per-order **claim marker** makes claims idempotent: the claim script returns `2` for a line
  this order already holds, which does not consume tokens again.
- **Refunds** (on rejection, release or expiry) happen at most once: the refund script deletes the
  claim marker atomically and returns tokens only if it existed.
- The gate is checked *before* the idempotency lookup, so rejected flash-sale traffic never
  reaches MongoDB. A retry of an already finished order refunds any tokens it newly claimed, so
  replays cannot leak tokens.

## Results

Setup: a 4 vCPU dev container, with k6 running on the same machine. 20,000 buyers and 300 concurrent
users compete for 1,000 units of one SKU. Ranges are from three warm-JVM runs per configuration.
Reproduce with `load-tests/flash-sale.js`.

| Configuration | Units sold | Errors | Throughput | p95 latency | Time to sell out |
|---|---|---|---|---|---|
| Per-SKU locks only (before group commit) | 1,000 | 7.1% (`503`) | ~880 req/s | 2.1 s | ~22 s |
| Group commit, gate disarmed | 1,000 | 0 | 2,550–2,750 req/s | 210–230 ms | < 1 s |
| Group commit, gate armed | 1,000 | 0 | 3,900–4,500 req/s | 170–225 ms | < 1 s |

Every run sold exactly 1,000 units. Under load, batches grew to 189–200 reservations per commit,
and a single instance needed no transaction retries. These numbers come from a shared dev box;
they show relative gains, not production capacity.

Integration tests (`ConcurrentReservationIT`) assert exactly-N success for 600 concurrent buyers
of 100 units with the gate on and off, multi-SKU orders whose lines arrive in both orders, and 200
concurrent retries of one order ID producing exactly one reservation.

## Alternatives considered

- **Redis as the source of truth for stock.** Fastest option, but Redis persistence (AOF every
  second) can lose acknowledged writes on failover, which here means oversold units. Rejected as
  the source of truth; kept as an admission gate.
- **Optimistic concurrency (version field) without transactions.** Same hot-document collisions,
  and no way to make reservation, stock and event atomic without a separate saga.
- **Pre-split stock into buckets** (e.g. 10 documents of 100 units each). Spreads contention, but
  complicates multi-line orders and "last unit" handling. Group commit removed the need for it.
- **Database-level queue** (e.g. Kafka partition per SKU, single consumer). Strong ordering, but
  adds a round trip through Kafka to every checkout request and makes synchronous responses
  awkward.

## Consequences and known limitations

- **Single-instance batching.** Batches form per instance. With many instances on one hot SKU,
  cross-instance conflicts come back; they are retried with backoff. Routing a hot SKU to one
  instance (consistent hashing at the gateway) is the next step if needed.
- **Gate drift.** Tokens are set when a sale is armed. Units freed by reservations that never went
  through the gate do not top it up, so the gate can report "sold out" while MongoDB still has
  stock. It never oversells. Re-arming resynchronises it, and restocks top it up automatically.
- **Outbox ordering after failures.** If publishing an event fails, its lease must expire before a
  retry, and a later event for the same reservation can overtake it. Consumers must treat
  `status` as last-writer-wins by `occurredAt`, or deduplicate on `eventId` and re-read state.
- **Retries across arming.** An order reserved *before* a sale was armed holds no gate claim. If
  it is retried after the sale sells out, it gets `409` instead of its original reservation.
  Clients can still read it with `GET /reservations/{id}`.
- **Sweeper latency.** Expiry is checked every 5 seconds. Confirming after `expiresAt` is rejected
  even if the sweeper has not run yet, so this delay never extends a hold.
