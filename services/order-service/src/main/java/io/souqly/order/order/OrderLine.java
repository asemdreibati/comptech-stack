package io.souqly.order.order;

import java.math.BigDecimal;
import java.util.Map;

/** One item, with the price and title the buyer saw at checkout. */
public record OrderLine(String sku, String productId, String sellerId, Map<String, String> title, int quantity,
        BigDecimal unitPrice) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
