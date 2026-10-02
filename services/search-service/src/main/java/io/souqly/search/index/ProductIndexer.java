package io.souqly.search.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.souqly.search.config.SearchProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

/**
 * Builds the search index from two streams that never coordinate with each other: listing
 * snapshots from the catalog and stock levels from inventory. One search document per SKU holds
 * both, and each half carries its own version.
 *
 * <p>Every write is a version-guarded scripted upsert: an event is applied only if it is newer
 * than the half of the document it updates. Late, duplicated or replayed events therefore change
 * nothing, which makes the whole pipeline idempotent: a failed batch is simply retried, and the
 * index can be rebuilt by replaying the compacted topics from the start.
 */
@Component
public class ProductIndexer {

    /** Applies {@code params.doc} only when {@code params.version} is newer than the stored one. */
    static final String APPLY_IF_NEWER = """
            if (ctx._source[params.versionField] == null || params.version > ctx._source[params.versionField]) {
              for (entry in params.doc.entrySet()) { ctx._source[entry.getKey()] = entry.getValue(); }
            } else {
              ctx.op = 'none';
            }""";

    private final OpenSearchClient opensearch;
    private final JsonMapper json;
    private final SearchProperties properties;
    private final Counter applied;
    private final Counter skipped;

    public ProductIndexer(OpenSearchClient opensearch, JsonMapper json, SearchProperties properties,
            MeterRegistry meterRegistry) {
        this.opensearch = opensearch;
        this.json = json;
        this.properties = properties;
        this.applied = meterRegistry.counter("souqly.search.index.updates", "result", "applied");
        this.skipped = meterRegistry.counter("souqly.search.index.updates", "result", "stale");
    }

    @KafkaListener(id = "search-indexer", batch = "true",
            topics = {"${souqly.search.topics.products}", "${souqly.search.topics.stock-levels}"})
    public void index(List<ConsumerRecord<String, String>> records) {
        StringBuilder bulk = new StringBuilder();
        List<Integer> positions = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            var record = records.get(i);
            if (record.value() == null) {
                continue; // a compaction tombstone: nothing to apply
            }
            Update update;
            try {
                update = toUpdate(record.topic(), json.readTree(record.value()));
            }
            catch (JacksonException | IllegalArgumentException ex) {
                // Unreadable events go to the dead-letter topic instead of blocking the partition.
                throw new BatchListenerFailedException("Unreadable event: " + ex.getMessage(), ex, i);
            }
            bulk.append(opensearch.toJson(Map.of("update", Map.of(
                            "_index", properties.opensearch().alias(), "_id", update.sku(), "retry_on_conflict", 5))))
                    .append('\n')
                    .append(opensearch.toJson(Map.of(
                            "scripted_upsert", true,
                            "upsert", Map.of(),
                            "script", Map.of("lang", "painless", "source", APPLY_IF_NEWER, "params", Map.of(
                                    "versionField", update.versionField(),
                                    "version", update.version(),
                                    "doc", update.fields())))))
                    .append('\n');
            positions.add(i);
        }
        if (positions.isEmpty()) {
            return;
        }
        var response = opensearch.bulk(bulk.toString());
        if (!response.ok()) {
            throw new BatchListenerFailedException("Bulk request failed with status " + response.status(), 0);
        }
        JsonNode items = response.body().path("items");
        for (int i = 0; i < items.size(); i++) {
            JsonNode result = items.get(i).path("update");
            if (result.has("error")) {
                // Everything before this record is applied; retrying from here is safe.
                throw new BatchListenerFailedException("Index update failed: " + result.get("error"), positions.get(i));
            }
            ("noop".equals(result.path("result").asString()) ? skipped : applied).increment();
        }
    }

    record Update(String sku, String versionField, long version, Map<String, Object> fields) {
    }

    Update toUpdate(String topic, JsonNode event) {
        String sku = required(event, "sku").asString();
        long version = required(event, "version").asLong();
        if (topic.equals(properties.topics().stockLevels())) {
            return new Update(sku, "stockVersion", version, Map.of(
                    "sku", sku,
                    "inStock", required(event, "available").asLong() > 0,
                    "stockVersion", version));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sku", sku);
        fields.put("productId", required(event, "productId").asString());
        fields.put("sellerId", event.path("sellerId").asString(null));
        fields.put("category", required(event, "category").asString());
        fields.put("title", json.convertValue(event.path("title"), Map.class));
        fields.put("description", json.convertValue(event.path("description"), Map.class));
        fields.put("brand", event.path("brand").asString(null));
        fields.put("brandLabel", event.path("brand").asString(null));
        fields.put("price", required(event.path("price"), "amount").decimalValue());
        fields.put("currency", event.path("price").path("currency").asString(null));
        fields.put("facets", json.convertValue(event.path("facets"), List.class));
        JsonNode images = event.path("imageUrls");
        fields.put("imageUrl", images.isArray() && !images.isEmpty() ? images.get(0).asString() : null);
        fields.put("visible", "ACTIVE".equals(event.path("status").asString()));
        fields.put("catalogVersion", version);
        fields.put("listedAt", event.path("createdAt").asString(null));
        return new Update(sku, "catalogVersion", version, fields);
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing field " + field);
        }
        return value;
    }
}
