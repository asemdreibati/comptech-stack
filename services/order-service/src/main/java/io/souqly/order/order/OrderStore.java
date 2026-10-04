package io.souqly.order.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.souqly.order.config.OrderProperties;
import io.souqly.platform.mongo.MongoTransactions;
import io.souqly.platform.outbox.OutboxWriter;
import org.bson.Document;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Persistence for orders and their saga state. Every status change is a conditional update on the
 * order's version, recorded in its history and published as an {@link OrderEvent}, all in one
 * MongoDB transaction.
 */
@Component
public class OrderStore {

    private final MongoTemplate mongo;
    private final MongoTransactions transactions;
    private final OutboxWriter outbox;
    private final OrderProperties properties;
    private final Clock clock;

    public OrderStore(MongoTemplate mongo, MongoTransactions transactions, OutboxWriter outbox,
            OrderProperties properties, Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    public Order insert(Order order) {
        return transactions.execute(() -> {
            mongo.insert(order);
            outbox.append(OrderEvent.messageFor(properties.topics().orders(), order, order.createdAt()));
            return order;
        });
    }

    public Order get(String id) {
        return mongo.findById(id, Order.class);
    }

    public Order findByKey(String buyerId, String idempotencyKey) {
        return mongo.findOne(query(where("buyerId").is(buyerId).and("idempotencyKey").is(idempotencyKey)),
                Order.class);
    }

    public List<Order> recentFor(String buyerId, int limit) {
        return mongo.find(query(where("buyerId").is(buyerId)).with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(limit), Order.class);
    }

    /**
     * Moves the order to {@code to} if nobody changed it since it was read, applying any extra
     * field changes. The next step is due immediately, and the attempt counter starts over.
     *
     * @return the updated order, or {@code null} if another instance got there first
     */
    public Order transition(Order order, OrderStatus to, Update changes, String note) {
        Instant now = clock.instant();
        Update update = (changes != null ? changes : new Update())
                .set("status", to)
                .push("history", new StatusChange(to, now, note))
                .set("attempts", 0)
                .unset("lastError")
                .set("nextAttemptAt", now)
                .inc("version", 1)
                .set("updatedAt", now);
        var guard = query(where("_id").is(order.id()).and("version").is(order.version()));
        return transactions.execute(() -> {
            Order updated = mongo.findAndModify(guard, update, FindAndModifyOptions.options().returnNew(true),
                    Order.class);
            if (updated != null) {
                outbox.append(OrderEvent.messageFor(properties.topics().orders(), updated, now));
            }
            return updated;
        });
    }

    /** Records a failed attempt and schedules the next one with jittered exponential backoff. */
    public Order scheduleRetry(Order order, String error) {
        var saga = properties.saga();
        int attempts = order.attempts() + 1;
        long ceiling = Math.min(saga.maxRetryBackoff().toMillis(),
                saga.retryBackoff().toMillis() << Math.min(attempts, 20));
        Duration delay = Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
        return mongo.findAndModify(query(where("_id").is(order.id()).and("version").is(order.version())),
                new Update().set("attempts", attempts).set("lastError", error)
                        .set("nextAttemptAt", clock.instant().plus(delay)).set("updatedAt", clock.instant()),
                FindAndModifyOptions.options().returnNew(true), Order.class);
    }

    /**
     * Takes the lease on an order so only one instance advances it at a time.
     *
     * @return the order, or {@code null} if another instance holds the lease
     */
    public Order claim(String id) {
        Instant now = clock.instant();
        return mongo.findAndModify(
                query(where("_id").is(id).orOperator(where("lockedUntil").is(null), where("lockedUntil").lt(now))),
                new Update().set("lockedUntil", now.plus(properties.saga().lease())),
                FindAndModifyOptions.options().returnNew(true), Order.class);
    }

    public void release(String id) {
        mongo.updateFirst(query(where("_id").is(id)), new Update().unset("lockedUntil"), Order.class);
    }

    /** Unfinished orders whose next step is due and whose lease (if any) has run out. */
    public List<String> due(int limit) {
        Instant now = clock.instant();
        var due = query(where("status").nin(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, OrderStatus.REJECTED,
                        OrderStatus.NEEDS_ATTENTION)
                .and("nextAttemptAt").lte(now)
                .orOperator(where("lockedUntil").is(null), where("lockedUntil").lt(now)))
                .with(Sort.by("nextAttemptAt")).limit(limit);
        due.fields().include("_id");
        // Raw documents: a projection cannot be mapped onto the Order record.
        return mongo.find(due, Document.class, mongo.getCollectionName(Order.class)).stream()
                .map(doc -> doc.getString("_id")).toList();
    }
}
