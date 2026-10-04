package io.souqly.order.pricing;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What checkout needs to know about a listing, replicated from the catalog's events: who sells it,
 * what it is called, what it costs and whether it can be bought.
 */
@Document("prices")
public record ProductPrice(
        @Id String sku,
        String productId,
        String sellerId,
        Map<String, String> title,
        BigDecimal amount,
        String currency,
        boolean purchasable,
        long version) {
}
