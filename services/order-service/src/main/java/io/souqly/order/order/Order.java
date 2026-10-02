package io.souqly.order.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An order and the state of its checkout saga.
 *
 * @param idempotencyKey   the buyer's {@code Idempotency-Key}; unique per buyer
 * @param requestHash      fingerprint of the request, to tell a retry from a reused key
 * @param paymentMethod    the PSP's payment-method token, kept only until payment is attempted
 * @param attempts         consecutive failed attempts at the current step
 * @param nextAttemptAt    when the recovery sweeper may next advance this order
 * @param lockedUntil      lease held by the instance advancing the order
 */
@Document("orders")
public record Order(
        @Id String id,
        String buyerId,
        String idempotencyKey,
        String requestHash,
        List<OrderLine> lines,
        BigDecimal total,
        String currency,
        String paymentMethod,
        String reservationId,
        String paymentId,
        OrderStatus status,
        Failure failure,
        List<StatusChange> history,
        int attempts,
        String lastError,
        Instant nextAttemptAt,
        Instant lockedUntil,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** The PSP idempotency key for this order's payment: the same on every retry. */
    public String paymentKey() {
        return "order-" + id;
    }

    public String refundKey() {
        return "refund-order-" + id;
    }
}
