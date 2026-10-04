package io.souqly.returns.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An order as the returns service sees it, replicated from order events.
 *
 * @param confirmedAt when the order was confirmed; the return window counts from here
 */
public record PurchasedOrder(String orderId, String buyerId, String status, String currency, String paymentId,
        List<Line> lines, Instant confirmedAt, long version) {

    public record Line(String sku, String productId, String sellerId, Map<String, String> title, int quantity,
            BigDecimal unitPrice) {
    }

    public boolean confirmed() {
        return "CONFIRMED".equals(status);
    }
}
