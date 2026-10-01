package io.souqly.inventory.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.souqly.inventory.config.InventoryProperties;
import io.souqly.inventory.flashsale.FlashSaleGate;
import io.souqly.inventory.outbox.OutboxWriter;
import io.souqly.inventory.stock.InsufficientStockException;
import io.souqly.inventory.stock.StockItem;
import io.souqly.inventory.support.MongoTransactions;
import io.souqly.inventory.support.SkuLocks;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Reserves stock for orders without overselling.
 *
 * <p>Every unit is guarded by a conditional update ({@code available >= qty}) inside a MongoDB
 * transaction that also writes the reservation and its outbox event, so stock, reservation and
 * event always agree. Three layers keep a hot SKU fast under load:
 * <ol>
 *   <li>the {@link FlashSaleGate} rejects excess flash-sale demand in Redis;</li>
 *   <li>a non-transactional read rejects requests for SKUs that are visibly sold out;</li>
 *   <li>{@link SkuLocks} queues writers per SKU so their transactions do not abort each other;</li>
 *   <li>the {@link ReservationCombiner} commits queued single-line orders in one transaction.</li>
 * </ol>
 * None of them is needed for correctness; they only decide how much work reaches MongoDB.
 */
@Service
public class ReservationService {

    private final MongoTemplate mongo;
    private final MongoTransactions transactions;
    private final SkuLocks locks;
    private final ReservationCombiner combiner;
    private final FlashSaleGate gate;
    private final OutboxWriter outbox;
    private final InventoryProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ReservationService(MongoTemplate mongo, MongoTransactions transactions, SkuLocks locks,
            ReservationCombiner combiner, FlashSaleGate gate, OutboxWriter outbox, InventoryProperties properties,
            Clock clock, MeterRegistry meterRegistry) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.locks = locks;
        this.combiner = combiner;
        this.gate = gate;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public record ReserveResult(Reservation reservation, boolean created) {
    }

    /**
     * Reserves all lines or none. Calling again with the same order ID and lines returns the
     * original reservation instead of reserving twice.
     */
    public ReserveResult reserve(String orderId, List<ReservationLine> requestedLines) {
        // A fixed SKU order keeps concurrent multi-line transactions from conflicting cyclically.
        List<ReservationLine> lines = requestedLines.stream()
                .sorted(Comparator.comparing(ReservationLine::sku))
                .toList();

        // The gate goes first so flash-sale rejections never touch MongoDB. That is safe for
        // retries: an order that already claimed its tokens is always admitted again.
        var decision = gate.claim(orderId, lines);
        if (!decision.admitted()) {
            record("gate_rejected");
            throw new InsufficientStockException(decision.soldOutSku(),
                    quantityOf(lines, decision.soldOutSku()), decision.tokensLeft());
        }

        Optional<Reservation> existing = findByOrderId(orderId);
        if (existing.isPresent()) {
            // A retry of a finished order (released, expired) must not keep fresh tokens.
            gate.refund(orderId, decision.newlyClaimed());
            return replay(existing.get(), lines);
        }

        try {
            rejectIfVisiblySoldOut(lines);
            Reservation created = createReservation(orderId, lines);
            record("created");
            return new ReserveResult(created, true);
        }
        catch (DuplicateKeyException ex) {
            // A concurrent request for the same order won the race. Its gate claim is the one
            // we observed, so nothing is refunded here.
            return replay(findByOrderId(orderId).orElseThrow(() -> ex), lines);
        }
        catch (RuntimeException ex) {
            gate.refund(orderId, decision.newlyClaimed());
            record(ex instanceof InsufficientStockException ? "insufficient_stock" : "error");
            throw ex;
        }
    }

    public Reservation get(String reservationId) {
        var reservation = mongo.findById(reservationId, Reservation.class);
        if (reservation == null) {
            throw new ReservationNotFoundException(reservationId);
        }
        return reservation;
    }

    /** The order was paid: reserved units leave the warehouse's sellable pool for good. */
    public Reservation confirm(String reservationId) {
        Instant now = clock.instant();
        return locks.withLocks(skusOf(get(reservationId).lines()), () -> transactions.execute(() -> {
            var updated = transition(where("_id").is(reservationId)
                    .and("status").is(ReservationStatus.PENDING)
                    .and("expiresAt").gt(now), ReservationStatus.CONFIRMED, now);
            if (updated == null) {
                var current = get(reservationId);
                if (current.status() == ReservationStatus.CONFIRMED) {
                    return current;
                }
                if (current.status() == ReservationStatus.PENDING) {
                    throw ReservationStateException.expired(current);
                }
                throw ReservationStateException.illegalTransition(current, ReservationStatus.CONFIRMED);
            }
            for (ReservationLine line : updated.lines()) {
                mongo.updateFirst(query(where("_id").is(line.sku())),
                        new Update().inc("reserved", -line.quantity()).set("updatedAt", now), StockItem.class);
            }
            appendEvent(updated, now);
            return updated;
        }));
    }

    /** The order was cancelled: units go back on sale. Releasing twice is a no-op. */
    public Reservation release(String reservationId) {
        Instant now = clock.instant();
        var released = locks.withLocks(skusOf(get(reservationId).lines()), () -> transactions.execute(() -> {
            var updated = returnToStock(where("_id").is(reservationId)
                    .and("status").is(ReservationStatus.PENDING), ReservationStatus.RELEASED, now);
            if (updated == null) {
                var current = get(reservationId);
                if (current.status() == ReservationStatus.RELEASED || current.status() == ReservationStatus.EXPIRED) {
                    return current;
                }
                throw ReservationStateException.illegalTransition(current, ReservationStatus.RELEASED);
            }
            return updated;
        }));
        gate.refund(released.orderId(), released.lines());
        return released;
    }

    /**
     * Expires a pending reservation whose deadline has passed. Returns false when another
     * instance or a confirm/release got there first.
     */
    boolean expire(String reservationId) {
        Instant now = clock.instant();
        var expired = locks.withLocks(skusOf(get(reservationId).lines()),
                () -> transactions.execute(() -> returnToStock(where("_id").is(reservationId)
                        .and("status").is(ReservationStatus.PENDING)
                        .and("expiresAt").lte(now), ReservationStatus.EXPIRED, now)));
        if (expired == null) {
            return false;
        }
        gate.refund(expired.orderId(), expired.lines());
        record("expired");
        return true;
    }

    /**
     * Fails fast, without a transaction or a lock, when a SKU is already short. The read may be
     * stale, which only ever lets a doomed request through to the authoritative check.
     */
    private void rejectIfVisiblySoldOut(List<ReservationLine> lines) {
        for (ReservationLine line : lines) {
            var stock = mongo.findById(line.sku(), StockItem.class);
            long available = stock != null ? stock.available() : 0;
            if (available < line.quantity()) {
                throw new InsufficientStockException(line.sku(), line.quantity(), available);
            }
        }
    }

    private Reservation createReservation(String orderId, List<ReservationLine> lines) {
        if (lines.size() == 1) {
            try {
                return combiner.reserve(orderId, lines.getFirst());
            }
            catch (ReservationCombiner.BatchAbortedException ex) {
                // Fall through to the one-at-a-time path, which resolves duplicate orders.
            }
        }
        return locks.withLocks(skusOf(lines), () -> transactions.execute(() -> insertReservation(orderId, lines)));
    }

    private Reservation insertReservation(String orderId, List<ReservationLine> lines) {
        Instant now = clock.instant();
        var reservation = new Reservation(UUID.randomUUID().toString(), orderId, lines, ReservationStatus.PENDING,
                now, now.plus(properties.reservationTtl()), now);
        // Inserted first so a duplicate order fails before any stock is touched.
        mongo.insert(reservation);
        for (ReservationLine line : lines) {
            var result = mongo.updateFirst(
                    query(where("_id").is(line.sku()).and("available").gte(line.quantity())),
                    new Update().inc("available", -line.quantity()).inc("reserved", line.quantity())
                            .set("updatedAt", now),
                    StockItem.class);
            if (result.getModifiedCount() == 0) {
                var stock = mongo.findById(line.sku(), StockItem.class);
                throw new InsufficientStockException(line.sku(), line.quantity(), stock != null ? stock.available() : 0);
            }
        }
        appendEvent(reservation, now);
        return reservation;
    }

    private Reservation returnToStock(Criteria criteria, ReservationStatus target, Instant now) {
        var updated = transition(criteria, target, now);
        if (updated == null) {
            return null;
        }
        for (ReservationLine line : updated.lines()) {
            mongo.updateFirst(query(where("_id").is(line.sku())),
                    new Update().inc("available", line.quantity()).inc("reserved", -line.quantity())
                            .set("updatedAt", now),
                    StockItem.class);
        }
        appendEvent(updated, now);
        return updated;
    }

    private Reservation transition(Criteria criteria, ReservationStatus target, Instant now) {
        return mongo.findAndModify(query(criteria),
                new Update().set("status", target).set("updatedAt", now),
                FindAndModifyOptions.options().returnNew(true), Reservation.class);
    }

    private void appendEvent(Reservation reservation, Instant now) {
        outbox.append(ReservationEvent.messageFor(reservation, now));
    }

    private ReserveResult replay(Reservation existing, List<ReservationLine> lines) {
        if (!existing.lines().equals(lines)) {
            throw new IdempotencyConflictException(existing.orderId());
        }
        record("replayed");
        return new ReserveResult(existing, false);
    }

    private Optional<Reservation> findByOrderId(String orderId) {
        return Optional.ofNullable(mongo.findOne(query(where("orderId").is(orderId)), Reservation.class));
    }

    private static List<String> skusOf(List<ReservationLine> lines) {
        return lines.stream().map(ReservationLine::sku).toList();
    }

    private static int quantityOf(List<ReservationLine> lines, String sku) {
        return lines.stream().filter(l -> l.sku().equals(sku)).mapToInt(ReservationLine::quantity).sum();
    }

    private void record(String outcome) {
        meterRegistry.counter("souqly.inventory.reservations", "outcome", outcome).increment();
    }
}
