package io.souqly.catalog;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.souqly.catalog.category.CategoryService;
import io.souqly.catalog.i18n.LocalizedText;
import io.souqly.catalog.product.Money;
import io.souqly.catalog.product.ProductService;
import io.souqly.catalog.product.ProductService.ListingDetails;
import io.souqly.platform.security.Caller;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductEventsIT {

    @Autowired
    CategoryService categories;

    @Autowired
    ProductService products;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    JsonMapper json;

    @Test
    void everyChangePublishesTheFullListingKeyedByProduct() {
        categories.save("phones", new LocalizedText("Phones", "هواتف"), "category-phones");
        var acme = new Caller("seller-1", "acme", Set.of("product:write"));
        var details = new ListingDetails("phones", new LocalizedText("Acme Phone", "هاتف أكمي"),
                new LocalizedText("Fast", "سريع"), "Acme", new Money(new BigDecimal("1999.00"), "AED"),
                Map.of("storage", "256", "color", "blue", "network", "5g", "screenInches", 6.7));

        var product = products.create("EVT-" + UUID.randomUUID(), details, acme);
        products.update(product.id(), 1, details, acme);

        List<JsonNode> events = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("catalog.products.v1"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (product.id().equals(record.key())) {
                        events.add(json.readTree(record.value()));
                    }
                });
                return events.size() >= 2;
            });
        }

        assertThat(events).extracting(e -> e.get("version").asLong()).containsExactly(1L, 2L);
        JsonNode latest = events.get(1);
        assertThat(latest.get("sku").asString()).isEqualTo(product.sku());
        assertThat(latest.get("sellerId").asString()).isEqualTo("acme");
        assertThat(latest.get("status").asString()).isEqualTo("DRAFT");
        assertThat(latest.get("title").get("ar").asString()).isEqualTo("هاتف أكمي");
        assertThat(latest.get("price").get("amount").decimalValue()).isEqualByComparingTo("1999.00");
        assertThat(latest.get("facets").findValuesAsString("name")).contains("storage", "color", "network");
    }
}
