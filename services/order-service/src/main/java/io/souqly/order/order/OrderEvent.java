package io.souqly.order.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.souqly.platform.outbox.OutboxWriter;

/**
 * The order's state after each saga step, keyed by order ID (event-carried state transfer).
 *
 * @param paymentId the PSP payment once captured, so returns can refund against it
 */
public record OrderEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String orderId,
        String buyerId,
        OrderStatus status,
        List<OrderLine> lines,
        BigDecimal total,
        String currency,
        Failure failure,
        String paymentId,
        long version) {

    static OutboxWriter.Message messageFor(String topic, Order order, Instant occurredAt) {
        String eventId = UUID.randomUUID().toString();
        String type = "Order" + switch (order.status()) {
            case PLACED -> "Placed";
            case STOCK_RESERVED -> "StockReserved";
            case PAID -> "Paid";
            case CONFIRMED -> "Confirmed";
            case RELEASING_STOCK, REFUNDING -> "Compensating";
            case CANCELLED -> "Cancelled";
            case REJECTED -> "Rejected";
            case NEEDS_ATTENTION -> "NeedsAttention";
        };
        var event = new OrderEvent(eventId, type, occurredAt, order.id(), order.buyerId(), order.status(),
                order.lines(), order.total(), order.currency(), order.failure(), order.paymentId(), order.version());
        return new OutboxWriter.Message(eventId, topic, "Order", order.id(), type, occurredAt, event);
    }
}
