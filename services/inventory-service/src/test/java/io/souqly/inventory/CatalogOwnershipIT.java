package io.souqly.inventory;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import io.souqly.inventory.stock.StockAccessDeniedException;
import io.souqly.inventory.stock.StockNotFoundException;
import io.souqly.inventory.stock.StockService;
import io.souqly.platform.security.Caller;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

/** The catalog decides who owns a SKU; inventory follows its listing events. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogOwnershipIT {

    static final Caller ACME = new Caller("acme-user", "acme", Set.of("stock:write"));
    static final Caller GLOBEX = new Caller("globex-user", "globex", Set.of("stock:write"));

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    StockService stock;

    @Test
    void listingSellerOwnsTheSkuBeforeAnyRestock() throws Exception {
        String sku = "OWN-" + UUID.randomUUID();
        listed(sku, "acme");
        await().atMost(Duration.ofSeconds(30)).ignoreException(StockNotFoundException.class)
                .untilAsserted(() -> assertThat(stock.get(sku).sellerId()).isEqualTo("acme"));
        assertThat(stock.get(sku).available()).isZero();

        // Another seller can no longer claim the SKU by restocking it first.
        assertThatExceptionOfType(StockAccessDeniedException.class)
                .isThrownBy(() -> stock.restock(sku, 10, GLOBEX))
                .satisfies(ex -> assertThat(ex.reason()).isEqualTo(StockAccessDeniedException.Reason.NOT_SKU_OWNER));
        assertThat(stock.restock(sku, 10, ACME).available()).isEqualTo(10);
    }

    @Test
    void existingOwnerIsNeverOverwritten() throws Exception {
        String sku = "OWN-" + UUID.randomUUID();
        stock.restock(sku, 3, GLOBEX);
        String marker = "OWN-" + UUID.randomUUID();
        listed(sku, "acme");
        listed(marker, "acme");
        await().atMost(Duration.ofSeconds(30)).ignoreException(StockNotFoundException.class)
                .untilAsserted(() -> assertThat(stock.get(marker).sellerId()).isEqualTo("acme"));

        assertThat(stock.get(sku).sellerId()).isEqualTo("globex");
        assertThat(stock.get(sku).available()).isEqualTo(3);
    }

    private void listed(String sku, String sellerId) throws Exception {
        kafka.send("catalog.products.v1", "prod-" + sku, """
                {"eventType": "ProductChanged", "productId": "prod-%s", "sku": "%s", "sellerId": "%s",
                 "status": "DRAFT", "version": 1}""".formatted(sku, sku, sellerId)).get();
    }
}
