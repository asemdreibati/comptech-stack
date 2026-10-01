package io.souqly.inventory.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.souqly.inventory.config.InventoryProperties;
import io.souqly.inventory.outbox.OutboxWriter;
import io.souqly.inventory.stock.InsufficientStockException;
import io.souqly.inventory.stock.StockItem;
import io.souqly.inventory.support.MongoTransactions;
import io.souqly.inventory.support.SkuLocks;
import io.souqly.inventory.support.TransactionContentionException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Group commit for single-line reservations on hot SKUs (the flat-combining pattern).
 *
 * <p>Each MongoDB commit waits for a journal flush, so committing reservations one at a time
 * caps a hot SKU at a few dozen per second. Instead, every request joins its SKU stripe's
 * queue and then takes the stripe lock. Whichever thread holds the lock drains the queue and
 * commits everything waiting in one transaction (one stock update, one bulk insert of
 * reservations and events), then hands each waiter its own outcome. Under load, batch size
 * grows with demand; when idle, a batch is a single request with no added latency.
 *
 * <p>Requests are allocated first come, first served. If a batch cannot be committed as a
 * whole because of a duplicate order ID, its requests are handed back with
 * {@link BatchAbortedException} and the caller retries them one by one.
 */
@Component
class ReservationCombiner {

    static final int MAX_BATCH = 200;

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<Request>[] queues = new ConcurrentLinkedQueue[SkuLocks.STRIPES];

    private final MongoTemplate mongo;
    private final MongoTransactions transactions;
    private final SkuLocks locks;
    private final OutboxWriter outbox;
    private final InventoryProperties properties;
    private final Clock clock;
    private final DistributionSummary batchSizes;

    ReservationCombiner(MongoTemplate mongo, MongoTransactions transactions, SkuLocks locks, OutboxWriter outbox,
            InventoryProperties properties, Clock clock, MeterRegistry meterRegistry) {
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ConcurrentLinkedQueue<>();
        }
        this.mongo = mongo;
        this.transactions = transactions;
        this.locks = locks;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
        this.batchSizes = DistributionSummary.builder("souqly.inventory.reservation.batch_size")
                .description("Reservations committed per group-commit transaction")
                .publishPercentiles(0.5, 0.99)
                .register(meterRegistry);
    }

    /** Thrown to a request whose batch could not be committed; reserve it individually instead. */
    static class BatchAbortedException extends RuntimeException {

        BatchAbortedException() {
            super(null, null, false, false);
        }
    }

    private record Request(String orderId, ReservationLine line, CompletableFuture<Reservation> outcome) {
    }

    Reservation reserve(String orderId, ReservationLine line) {
        var request = new Request(orderId, line, new CompletableFuture<>());
        var queue = queues[SkuLocks.stripe(line.sku())];
        queue.add(request);
        try {
            locks.withLocks(List.of(line.sku()), () -> {
                // Our request is either already done by an earlier lock holder or still queued.
                while (!request.outcome().isDone()) {
                    var batch = drain(queue);
                    if (batch.isEmpty()) {
                        break;
                    }
                    commit(batch);
                }
                return null;
            });
        }
        catch (TransactionContentionException ex) {
            // Timed out waiting for the lock. If no batch picked the request up, it never ran.
            if (queue.remove(request)) {
                throw ex;
            }
        }
        try {
            return request.outcome().join();
        }
        catch (CompletionException ex) {
            throw (RuntimeException) ex.getCause();
        }
    }

    private static List<Request> drain(ConcurrentLinkedQueue<Request> queue) {
        List<Request> batch = new ArrayList<>();
        Request next;
        while (batch.size() < MAX_BATCH && (next = queue.poll()) != null) {
            batch.add(next);
        }
        return batch;
    }

    private void commit(List<Request> batch) {
        // The same order twice in one batch would hit the unique index and sink everyone else.
        Set<String> orderIds = new HashSet<>();
        List<Request> unique = new ArrayList<>(batch.size());
        for (Request request : batch) {
            if (orderIds.add(request.orderId())) {
                unique.add(request);
            }
            else {
                request.outcome().completeExceptionally(new BatchAbortedException());
            }
        }
        try {
            Map<Request, Object> outcomes = transactions.execute(() -> allocate(unique));
            batchSizes.record(unique.size());
            outcomes.forEach((request, outcome) -> {
                if (outcome instanceof Reservation reservation) {
                    request.outcome().complete(reservation);
                }
                else {
                    request.outcome().completeExceptionally((RuntimeException) outcome);
                }
            });
        }
        catch (DuplicateKeyException ex) {
            unique.forEach(r -> r.outcome().completeExceptionally(new BatchAbortedException()));
        }
        catch (RuntimeException ex) {
            unique.forEach(r -> r.outcome().completeExceptionally(ex));
        }
    }

    /** Runs inside the transaction and may be retried, so it only touches MongoDB. */
    private Map<Request, Object> allocate(List<Request> batch) {
        Instant now = clock.instant();
        Map<Request, Object> outcomes = new IdentityHashMap<>();
        Map<String, List<Request>> bySku = new LinkedHashMap<>();
        batch.forEach(r -> bySku.computeIfAbsent(r.line().sku(), sku -> new ArrayList<>()).add(r));

        List<Reservation> accepted = new ArrayList<>();
        bySku.forEach((sku, requests) -> {
            var stock = mongo.findById(sku, StockItem.class);
            long remaining = stock != null ? stock.available() : 0;
            long taken = 0;
            for (Request request : requests) {
                int quantity = request.line().quantity();
                if (quantity > remaining) {
                    outcomes.put(request, new InsufficientStockException(sku, quantity, remaining));
                    continue;
                }
                remaining -= quantity;
                taken += quantity;
                var reservation = new Reservation(UUID.randomUUID().toString(), request.orderId(),
                        List.of(request.line()), ReservationStatus.PENDING, now,
                        now.plus(properties.reservationTtl()), now);
                accepted.add(reservation);
                outcomes.put(request, reservation);
            }
            if (taken > 0) {
                var result = mongo.updateFirst(query(where("_id").is(sku).and("available").gte(taken)),
                        new Update().inc("available", -taken).inc("reserved", taken).set("updatedAt", now),
                        StockItem.class);
                if (result.getModifiedCount() != 1) {
                    // Cannot happen inside a snapshot transaction; fail loudly rather than oversell.
                    throw new IllegalStateException("Stock for " + sku + " changed during the transaction");
                }
            }
        });

        if (!accepted.isEmpty()) {
            mongo.insert(accepted, Reservation.class);
            outbox.appendAll(accepted.stream().map(r -> ReservationEvent.messageFor(r, now)).toList());
        }
        return outcomes;
    }
}
