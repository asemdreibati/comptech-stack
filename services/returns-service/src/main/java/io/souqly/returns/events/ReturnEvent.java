package io.souqly.returns.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.souqly.returns.returns.ReturnReason;
import io.souqly.returns.returns.ReturnStatus;

/** A return's state after a workflow step, keyed by return ID (event-carried state transfer). */
public record ReturnEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        UUID returnId,
        String orderId,
        String buyerId,
        String sellerId,
        ReturnStatus status,
        ReturnReason reason,
        BigDecimal refundAmount,
        String currency,
        String refundId,
        long version) {
}
