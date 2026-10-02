package io.souqly.inventory.stock;

import java.time.Clock;
import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Makes the catalog the authority on who owns a SKU. When a listing appears, its stock record is
 * created with the listing's seller as owner (and nothing in stock), so the seller's first
 * restock succeeds and nobody else can claim the SKU by restocking it first.
 *
 * <p>The upsert only sets fields on insert: an existing record is never modified, and an owner
 * that disagrees with the catalog is reported as a conflict for operations to resolve rather
 * than silently reassigned.
 */
@Component
class CatalogOwnershipListener {

    private static final Logger log = LoggerFactory.getLogger(CatalogOwnershipListener.class);

    private final MongoTemplate mongo;
    private final JsonMapper json;
    private final Clock clock;
    private final Counter conflicts;

    CatalogOwnershipListener(MongoTemplate mongo, JsonMapper json, Clock clock, MeterRegistry meterRegistry) {
        this.mongo = mongo;
        this.json = json;
        this.clock = clock;
        this.conflicts = Counter.builder("souqly.inventory.ownership.conflicts")
                .description("SKUs whose stock owner differs from the catalog listing's seller")
                .register(meterRegistry);
    }

    @KafkaListener(id = "inventory-catalog-ownership", topics = "${souqly.inventory.topics.catalog-products}")
    void onListingChanged(String payload) {
        JsonNode listing;
        try {
            listing = json.readTree(payload);
        }
        catch (JacksonException ex) {
            log.warn("Skipping unreadable catalog event: {}", ex.getMessage());
            return;
        }
        String sku = listing.path("sku").asString(null);
        if (sku == null) {
            return;
        }
        String sellerId = listing.path("sellerId").asString(null);
        var update = new Update()
                .setOnInsert("sellerId", sellerId)
                .setOnInsert("available", 0L)
                .setOnInsert("reserved", 0L)
                .setOnInsert("version", 0L)
                .setOnInsert("updatedAt", clock.instant());
        StockItem stock = mongo.findAndModify(query(where("_id").is(sku)), update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), StockItem.class);
        if (stock != null && !Objects.equals(stock.sellerId(), sellerId)) {
            conflicts.increment();
            log.warn("SKU {} is stocked by seller {} but listed by seller {}", sku, stock.sellerId(), sellerId);
        }
    }
}
