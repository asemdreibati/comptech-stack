package io.souqly.returns.returns;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A request to return items of one order to one seller (a basket from several sellers becomes
 * several returns, as each seller decides on their own).
 *
 * @param refundAmount what the buyer paid for the returned items
 * @param refundId     the PSP refund, once made
 */
public record ReturnRequest(
        UUID id,
        String orderId,
        String buyerId,
        String sellerId,
        String idempotencyKey,
        String requestHash,
        ReturnReason reason,
        String comment,
        List<Line> lines,
        BigDecimal refundAmount,
        String currency,
        ReturnStatus status,
        String refundId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant decidedAt) {

    public record Line(String sku, Map<String, String> title, int quantity, BigDecimal unitPrice) {
    }

    public String refundKey() {
        return "return-" + id;
    }
}
