package io.souqly.order.saga;

import io.micrometer.core.instrument.MeterRegistry;
import io.souqly.order.config.OrderProperties;
import io.souqly.order.inventory.InventoryClient;
import io.souqly.order.order.Failure;
import io.souqly.order.order.Order;
import io.souqly.order.order.OrderStatus;
import io.souqly.order.order.OrderStore;
import io.souqly.platform.payments.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Orchestrates checkout: reserve stock, take payment, confirm the reservation.
 *
 * <p>Three rules make it safe to crash, retry or run on several instances at once:
 * <ol>
 *   <li><b>Every remote step is idempotent</b>: reservations are keyed by order ID, payments and
 *       refunds by order-derived PSP idempotency keys, and confirm/release are no-ops when
 *       repeated. Re-running a step after a crash or timeout never acts twice.</li>
 *   <li><b>Every result is persisted before the next step</b>, as a version-guarded transition
 *       written together with its event. The order's status always says what is done.</li>
 *   <li><b>One instance advances an order at a time</b>, holding a lease. If it dies, the lease
 *       expires and the recovery sweeper continues from the persisted status.</li>
 * </ol>
 * "Unknown" outcomes (timeouts, 5xx) never become decisions: the step is retried with backoff.
 * Business refusals after something was done trigger compensation: a declined payment releases
 * the stock; a reservation that expired before it could be confirmed refunds the payment.
 */
@Component
public class CheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);

    private final OrderStore orders;
    private final InventoryClient inventory;
    private final PaymentGateway payments;
    private final OrderProperties.Saga config;
    private final MeterRegistry meterRegistry;

    public CheckoutSaga(OrderStore orders, InventoryClient inventory, PaymentGateway payments,
            OrderProperties properties, MeterRegistry meterRegistry) {
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
        this.config = properties.saga();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Advances the order as far as it can go now. Returns its state afterwards: terminal, or
     * waiting for a retry that the recovery sweeper will run.
     */
    public Order advance(String orderId) {
        Order order = orders.claim(orderId);
        if (order == null) {
            return orders.get(orderId); // another instance is advancing it
        }
        try {
            while (!order.status().terminal()) {
                Order next = step(order);
                if (next == null) {
                    return orders.get(orderId); // lost a race; whoever won carries on
                }
                if (next.version() == order.version()) {
                    return next; // a retry was scheduled
                }
                order = next;
            }
            meterRegistry.counter("souqly.order.completed", "status", order.status().name()).increment();
            return order;
        }
        finally {
            orders.release(orderId);
        }
    }

    private Order step(Order order) {
        return switch (order.status()) {
            case PLACED -> reserveStock(order);
            case STOCK_RESERVED -> takePayment(order);
            case PAID -> confirmReservation(order);
            case RELEASING_STOCK -> releaseStock(order);
            case REFUNDING -> refundPayment(order);
            case CONFIRMED, CANCELLED, REJECTED, NEEDS_ATTENTION -> order;
        };
    }

    private Order reserveStock(Order order) {
        return switch (inventory.reserve(order.id(), order.lines())) {
            case InventoryClient.Done done -> orders.transition(order, OrderStatus.STOCK_RESERVED,
                    new Update().set("reservationId", done.reservationId()), "stock reserved");
            case InventoryClient.Refused refused -> orders.transition(order, OrderStatus.REJECTED,
                    failure(refused.code().equals("INSUFFICIENT_STOCK") ? "OUT_OF_STOCK" : refused.code(),
                            refused.detail()), "rejected by inventory");
            case InventoryClient.Unavailable unavailable -> retry(order, unavailable.reason());
        };
    }

    private Order takePayment(Order order) {
        var outcome = payments.charge(order.paymentKey(), order.id(), order.total(), order.currency(),
                order.paymentMethod());
        return switch (outcome) {
            case PaymentGateway.Succeeded paid -> orders.transition(order, OrderStatus.PAID,
                    new Update().set("paymentId", paid.paymentId()).unset("paymentMethod"), "payment captured");
            case PaymentGateway.Declined declined -> orders.transition(order, OrderStatus.RELEASING_STOCK,
                    failure("PAYMENT_DECLINED", declined.message()).unset("paymentMethod"),
                    "payment declined (" + declined.code() + "), releasing stock");
            case PaymentGateway.Unknown unknown -> retry(order, unknown.reason());
        };
    }

    private Order confirmReservation(Order order) {
        return switch (inventory.confirm(order.reservationId())) {
            case InventoryClient.Done done -> orders.transition(order, OrderStatus.CONFIRMED, null, "stock confirmed");
            case InventoryClient.Refused refused -> orders.transition(order, OrderStatus.REFUNDING,
                    failure("RESERVATION_EXPIRED", "Stock was no longer held when payment completed"),
                    "reservation " + refused.code() + ", refunding payment");
            case InventoryClient.Unavailable unavailable -> retry(order, unavailable.reason());
        };
    }

    private Order releaseStock(Order order) {
        return switch (inventory.release(order.reservationId())) {
            // Released now, or already released or expired: either way the stock is back.
            case InventoryClient.Done done -> orders.transition(order, OrderStatus.CANCELLED, null, "stock released");
            case InventoryClient.Refused refused when refused.code().equals("NOT_FOUND")
                    || refused.code().equals("RESERVATION_NOT_FOUND") ->
                    orders.transition(order, OrderStatus.CANCELLED, null, "no reservation left to release");
            case InventoryClient.Refused refused -> attention(order, "release refused: " + refused.code());
            case InventoryClient.Unavailable unavailable -> retry(order, unavailable.reason());
        };
    }

    private Order refundPayment(Order order) {
        return switch (payments.refund(order.refundKey(), order.paymentId())) {
            case PaymentGateway.Succeeded refunded -> orders.transition(order, OrderStatus.CANCELLED, null,
                    "payment refunded");
            case PaymentGateway.Declined declined -> attention(order, "refund refused: " + declined.code());
            case PaymentGateway.Unknown unknown -> retry(order, unknown.reason());
        };
    }

    private Order retry(Order order, String reason) {
        if (order.attempts() + 1 >= config.maxAttempts()) {
            return attention(order, "gave up after " + config.maxAttempts() + " attempts: " + reason);
        }
        log.info("Order {} step {} will be retried: {}", order.id(), order.status(), reason);
        Order scheduled = orders.scheduleRetry(order, reason);
        return scheduled != null ? scheduled : null;
    }

    /** Money or stock may be in limbo: stop and let a person decide. */
    private Order attention(Order order, String reason) {
        log.error("Order {} needs attention in {}: {}", order.id(), order.status(), reason);
        return orders.transition(order, OrderStatus.NEEDS_ATTENTION,
                new Update().set("failure", new Failure("NEEDS_ATTENTION", reason)), reason);
    }

    private static Update failure(String code, String message) {
        return new Update().set("failure", new Failure(code, message));
    }
}
