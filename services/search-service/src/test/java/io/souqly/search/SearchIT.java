package io.souqly.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Events go in through Kafka exactly as the catalog and inventory publish them; results come out
 * of the public API. Each test uses its own category so tests cannot see each other's products.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SearchIT {

    static final String PRODUCTS = "catalog.products.v1";
    static final String STOCK = "inventory.stock-levels.v1";

    @Autowired
    MockMvc mvc;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    JsonMapper json;

    @Test
    void matchesEnglishWithTyposAndArabicSpellingVariants() throws Exception {
        String category = newCategory();
        product(category, "SKU-" + UUID.randomUUID(), 1, "ACTIVE", "Acme Galaxy Phone", "هاتف آيفون ذكي", "Acme", "2499.00",
                facet("storage", "128"));

        awaitTotal(1, "category", category);
        assertThat(total("q", "phoen", "category", category)).as("typo").isEqualTo(1);
        assertThat(total("q", "ايفون", "category", category)).as("alef with madda spelled plain").isEqualTo(1);
        assertThat(total("q", "الهاتف", "category", category)).as("definite article").isEqualTo(1);
        assertThat(total("q", "tablet", "category", category)).isZero();
    }

    @Test
    void onlyLiveListingsAreSearchable() throws Exception {
        String category = newCategory();
        String sku = "SKU-" + UUID.randomUUID();
        product(category, sku, 1, "DRAFT", "Draft Phone", "هاتف", "Acme", "100.00");
        product(category, sku + "-marker", 1, "ACTIVE", "Marker Phone", "هاتف", "Acme", "100.00");
        awaitTotal(1, "category", category);

        product(category, sku, 2, "ACTIVE", "Draft Phone", "هاتف", "Acme", "100.00");
        awaitTotal(2, "category", category);

        product(category, sku, 3, "ARCHIVED", "Draft Phone", "هاتف", "Acme", "100.00");
        awaitTotal(1, "category", category);
    }

    @Test
    void lateEventsNeverOverwriteNewerState() throws Exception {
        String category = newCategory();
        String sku = "SKU-" + UUID.randomUUID();
        product(category, sku, 3, "ACTIVE", "Current title", "عنوان", "Acme", "300.00");
        awaitTotal(1, "category", category, "q", "current");

        // Version 2 arrives late; version 4 follows on the same partition, so once 4 is visible, 2 was seen.
        product(category, sku, 2, "ACTIVE", "Stale title", "عنوان", "Acme", "200.00");
        product(category, sku, 4, "ACTIVE", "Current title", "عنوان", "Acme", "400.00");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                search("category", category).at("/items/0/price").decimalValue()).isEqualByComparingTo("400.00"));
        assertThat(total("q", "stale", "category", category)).isZero();
    }

    @Test
    void stockLevelsAndListingsMergeWhicheverArrivesFirst() throws Exception {
        String category = newCategory();
        String sku = "SKU-" + UUID.randomUUID();
        stock(sku, 1, 5);
        product(category, sku, 1, "ACTIVE", "Stocked Phone", "هاتف", "Acme", "100.00");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(search("category", category).at("/items/0/inStock").asBoolean()).isTrue());

        stock(sku, 3, 0);
        stock(sku, 2, 7); // late: older than the sell-out
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(search("category", category).at("/items/0/inStock").asBoolean()).isFalse());
        assertThat(total("category", category, "inStock", "true")).isZero();
        assertThat(total("category", category)).isEqualTo(1);
    }

    @Test
    void facetCountsIgnoreTheirOwnSelection() throws Exception {
        String category = newCategory();
        product(category, "SKU-" + UUID.randomUUID(), 1, "ACTIVE", "Phone A", "هاتف", "Acme", "100.00",
                facet("storage", "128"), facet("color", "black"));
        product(category, "SKU-" + UUID.randomUUID(), 1, "ACTIVE", "Phone B", "هاتف", "Acme", "200.00",
                facet("storage", "256"), facet("color", "black"));
        product(category, "SKU-" + UUID.randomUUID(), 1, "ACTIVE", "Phone C", "هاتف", "Globex", "300.00",
                facet("storage", "256"), facet("color", "blue"));
        awaitTotal(3, "category", category);

        JsonNode result = search("category", category, "f.storage", "128");
        assertThat(result.get("total").asLong()).isEqualTo(1);
        // Storage still offers 256 (2 phones), so the shopper can widen the choice...
        assertThat(count(result, "storage", "128")).isEqualTo(1);
        assertThat(count(result, "storage", "256")).isEqualTo(2);
        // ...while other facets reflect the selection: only black phones have 128 GB.
        assertThat(count(result, "color", "black")).isEqualTo(1);
        assertThat(count(result, "color", "blue")).isZero();
        assertThat(attribute(result, "storage").get("label").asString()).isEqualTo("Storage");

        JsonNode both = search("category", category, "f.storage", "128", "f.storage", "256", "brand", "ACME");
        assertThat(both.get("total").asLong()).as("values OR-ed, brand case-insensitive").isEqualTo(2);
    }

    @Test
    void filtersAndSortsByPrice() throws Exception {
        String category = newCategory();
        for (String price : List.of("50.00", "150.00", "250.00")) {
            product(category, "SKU-" + UUID.randomUUID(), 1, "ACTIVE", "Phone " + price, "هاتف", "Acme", price);
        }
        awaitTotal(3, "category", category);

        JsonNode result = search("category", category, "minPrice", "100", "sort", "price_desc");
        assertThat(result.get("total").asLong()).isEqualTo(2);
        assertThat(result.at("/items/0/price").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(result.at("/items/1/price").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(result.at("/facets/price/min").decimalValue()).isEqualByComparingTo("150.00");
    }

    @Test
    void suggestsTitlesFromPrefixes() throws Exception {
        String category = newCategory();
        String sku = "SKU-" + UUID.randomUUID();
        String word = "Zephyr" + UUID.randomUUID().toString().substring(0, 6);
        product(category, sku, 1, "ACTIVE", word + " Phone", "هاتف زفير", "Acme", "100.00");
        awaitTotal(1, "category", category);

        mvc.perform(get("/api/v1/search/suggest").param("q", word.substring(0, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sku == '" + sku + "')].text").value(word + " Phone"));
        mvc.perform(get("/api/v1/search/suggest").param("q", "زفي").param("lang", "ar"))
                .andExpect(jsonPath("$[?(@.sku == '" + sku + "')].text").value("هاتف زفير"));
    }

    @Test
    void unreadableEventsAreParkedWithoutBlockingTheirPartition() throws Exception {
        String category = newCategory();
        String sku = "SKU-" + UUID.randomUUID();
        kafka.send(PRODUCTS, sku, "{ not json").get();
        product(category, sku, 1, "ACTIVE", "After Poison", "هاتف", "Acme", "100.00");

        awaitTotal(1, "category", category);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(PRODUCTS + "-dlt"));
            List<String> parked = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (sku.equals(r.key())) {
                        parked.add(r.value());
                    }
                });
                return !parked.isEmpty();
            });
            assertThat(parked).containsExactly("{ not json");
        }
    }

    @Test
    void rejectsUnreasonableRequests() throws Exception {
        mvc.perform(get("/api/v1/search").param("page", "600").param("size", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH"));
        mvc.perform(get("/api/v1/search").param("sort", "random")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("f.bad name!", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search").param("size", "500")).andExpect(status().isBadRequest());
    }

    // --- event fixtures, shaped exactly like the catalog's and inventory's events ---

    private void product(String category, String sku, long version, String status, String titleEn, String titleAr,
            String brand, String price, Map<?, ?>... facets) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "ProductChanged");
        event.put("productId", "prod-" + sku);
        event.put("sku", sku);
        event.put("sellerId", "acme");
        event.put("category", category);
        event.put("title", Map.of("en", titleEn, "ar", titleAr));
        event.put("description", Map.of("en", "A great device", "ar", "جهاز رائع"));
        event.put("brand", brand);
        event.put("price", Map.of("amount", new java.math.BigDecimal(price), "currency", "AED"));
        event.put("facets", List.of(facets));
        event.put("imageUrls", List.of("https://cdn.example/" + sku + ".png"));
        event.put("status", status);
        event.put("version", version);
        event.put("createdAt", "2026-10-01T10:00:00Z");
        kafka.send(PRODUCTS, "prod-" + sku, json.writeValueAsString(event)).get();
    }

    private void stock(String sku, long version, long available) throws Exception {
        kafka.send(STOCK, sku, json.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(),
                "eventType", "StockLevelChanged", "sku", sku, "available", available, "reserved", 0,
                "version", version))).get();
    }

    private static Map<String, String> facet(String name, String value) {
        String label = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return Map.of("name", name, "label", label, "value", value, "valueLabel", value.toUpperCase());
    }

    // --- search helpers ---

    private JsonNode search(String... params) throws Exception {
        var request = get("/api/v1/search");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private long total(String... params) throws Exception {
        return search(params).get("total").asLong();
    }

    private void awaitTotal(long expected, String... params) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(total(params)).isEqualTo(expected));
    }

    private static JsonNode attribute(JsonNode result, String name) {
        for (JsonNode attribute : result.at("/facets/attributes")) {
            if (attribute.get("name").asString().equals(name)) {
                return attribute;
            }
        }
        throw new AssertionError("No facet " + name + " in " + result.at("/facets/attributes"));
    }

    private static long count(JsonNode result, String name, String value) {
        for (JsonNode bucket : attribute(result, name).get("values")) {
            if (bucket.get("value").asString().equals(value)) {
                return bucket.get("count").asLong();
            }
        }
        return 0;
    }

    private static String newCategory() {
        return "cat-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
