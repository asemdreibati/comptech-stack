package io.souqly.order.pricing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Keeps the price book in step with the catalog's compacted listing topic. */
@Component
class PriceListener {

    private static final Logger log = LoggerFactory.getLogger(PriceListener.class);

    private final PriceBook prices;
    private final JsonMapper json;

    PriceListener(PriceBook prices, JsonMapper json) {
        this.prices = prices;
        this.json = json;
    }

    @KafkaListener(id = "order-price-book", topics = "${souqly.order.topics.catalog-products}")
    void onListingChanged(String payload) {
        JsonNode listing;
        try {
            listing = json.readTree(payload);
        }
        catch (JacksonException ex) {
            log.warn("Skipping unreadable catalog event: {}", ex.getMessage());
            return;
        }
        if (!listing.hasNonNull("sku") || !listing.path("price").hasNonNull("amount")) {
            return;
        }
        Map<String, String> title = new LinkedHashMap<>();
        listing.path("title").properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                title.put(e.getKey(), e.getValue().asString());
            }
        });
        prices.apply(new ProductPrice(
                listing.get("sku").asString(),
                listing.path("productId").asString(null),
                listing.path("sellerId").asString(null),
                title,
                listing.path("price").get("amount").decimalValue(),
                listing.path("price").path("currency").asString(null),
                "ACTIVE".equals(listing.path("status").asString()),
                listing.path("version").asLong()));
    }
}
