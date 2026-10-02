package io.souqly.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.souqly.order.pricing.PriceBook;
import io.souqly.order.pricing.ProductPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.souqly.order.CheckoutSagaIT.buyer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderApiIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    WireMockServer fakes;

    @Autowired
    PriceBook prices;

    @Autowired
    KafkaTemplate<String, String> kafka;

    String buyer;
    String sku;

    @BeforeEach
    void fakesThatSayYes() {
        fakes.resetAll();
        fakes.stubFor(post("/token").willReturn(okJson("""
                {"access_token": "service-token", "token_type": "Bearer", "expires_in": 300}""")));
        fakes.stubFor(post("/inventory/api/v1/reservations").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"id": "res-ok", "status": "PENDING"}""")));
        fakes.stubFor(post("/inventory/api/v1/reservations/res-ok/confirm").willReturn(okJson("""
                {"id": "res-ok", "status": "CONFIRMED"}""")));
        fakes.stubFor(post("/psp/v1/payment_intents").willReturn(okJson("""
                {"id": "pi_ok", "status": "succeeded"}""")));
        buyer = "buyer-" + UUID.randomUUID();
        sku = "SKU-" + UUID.randomUUID();
        price(sku, "10.00", "AED", true, 1);
    }

    @Test
    void retryingACheckoutReturnsTheSameOrder() throws Exception {
        String id = json.readTree(checkout(buyer(buyer), "retry-1", items(sku, 1))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("id").asString();

        // Same key, same basket: the same order comes back, and the buyer is charged once.
        checkout(buyer(buyer), "retry-1", items(sku, 1))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        fakes.verify(exactly(1), postRequestedFor(urlPathEqualTo("/psp/v1/payment_intents")));

        // Same key, different basket: refused, not answered with the wrong order.
        checkout(buyer(buyer), "retry-1", items(sku, 2))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // Keys are per buyer: another buyer may use the same one.
        checkout(buyer("buyer-" + UUID.randomUUID()), "retry-1", items(sku, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)));
    }

    @Test
    void buyersSeeOnlyTheirOwnOrders() throws Exception {
        String id = json.readTree(checkout(buyer(buyer), "mine-1", items(sku, 1))
                        .andReturn().getResponse().getContentAsString())
                .get("id").asString();

        mvc.perform(get("/api/v1/orders/{id}", id).with(buyer(buyer))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders").with(buyer(buyer)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id));

        // Another buyer cannot tell the order exists.
        String other = "buyer-" + UUID.randomUUID();
        mvc.perform(get("/api/v1/orders/{id}", id).with(buyer(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(get("/api/v1/orders").with(buyer(other))).andExpect(jsonPath("$", hasSize(0)));

        // Support staff can read any order.
        mvc.perform(get("/api/v1/orders/{id}", id).with(jwt().jwt(t -> t.subject("support-1"))
                        .authorities(new SimpleGrantedAuthority("order:read-any"))))
                .andExpect(status().isOk());
    }

    @Test
    void checkoutNeedsTheRightPermission() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON).content(items(sku, 1)))
                .andExpect(status().isUnauthorized());
        checkout(jwt().jwt(t -> t.subject("seller-1")).authorities(new SimpleGrantedAuthority("product:write")),
                "k", items(sku, 1))
                .andExpect(status().isForbidden());
        // Reading any order does not allow placing one.
        checkout(jwt().jwt(t -> t.subject("support-1")).authorities(new SimpleGrantedAuthority("order:read-any")),
                "k", items(sku, 1))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyLiveProductsWithAPriceCanBeBought() throws Exception {
        String draft = "SKU-" + UUID.randomUUID();
        price(draft, "10.00", "AED", false, 1);
        String unknown = "SKU-" + UUID.randomUUID();

        checkout(buyer(buyer), "unavailable-1", """
                {"items": [{"sku": "%s", "quantity": 1}, {"sku": "%s", "quantity": 1}, {"sku": "%s", "quantity": 1}],
                 "paymentMethod": "pm_card_visa"}""".formatted(sku, draft, unknown))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"))
                .andExpect(jsonPath("$.skus", hasSize(2)))
                .andExpect(jsonPath("$.skus", hasItem(draft)))
                .andExpect(jsonPath("$.skus", hasItem(unknown)));

        String inSar = "SKU-" + UUID.randomUUID();
        price(inSar, "10.00", "SAR", true, 1);
        checkout(buyer(buyer), "mixed-1", """
                {"items": [{"sku": "%s", "quantity": 1}, {"sku": "%s", "quantity": 1}],
                 "paymentMethod": "pm_card_visa"}""".formatted(sku, inSar))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MIXED_CURRENCY"));
        fakes.verify(exactly(0), postRequestedFor(urlPathEqualTo("/inventory/api/v1/reservations")));
    }

    @Test
    void malformedCheckoutsAreRejected() throws Exception {
        checkout(buyer(buyer), "bad-1", items(sku, 11))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        checkout(buyer(buyer), "bad-2", """
                {"items": [{"sku": "%1$s", "quantity": 1}, {"sku": "%1$s", "quantity": 2}],
                 "paymentMethod": "pm_card_visa"}""".formatted(sku))
                .andExpect(status().isBadRequest());
        checkout(buyer(buyer), "bad-3", """
                {"items": [], "paymentMethod": "pm_card_visa"}""")
                .andExpect(status().isBadRequest());
        checkout(buyer(buyer), "has spaces", items(sku, 1)).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(buyer(buyer)).contentType(MediaType.APPLICATION_JSON).content(items(sku, 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePriceBookFollowsCatalogEventsAndIgnoresStaleOnes() throws Exception {
        String listed = "SKU-" + UUID.randomUUID();
        publishListing(listed, 2, "ACTIVE", "25.00");
        publishListing(listed, 1, "ACTIVE", "99.00"); // late, older event
        publishListing(listed, 3, "ACTIVE", "20.00");
        publishListing(listed, 2, "DRAFT", "25.00"); // replayed

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<ProductPrice> found = prices.find(List.of(listed));
            assertThat(found).singleElement().satisfies(price -> {
                assertThat(price.version()).isEqualTo(3);
                assertThat(price.amount()).isEqualByComparingTo("20.00");
                assertThat(price.purchasable()).isTrue();
                assertThat(price.title()).containsEntry("ar", "هاتف");
            });
        });

        checkout(buyer(buyer), "priced-1", items(listed, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(20.0))
                .andExpect(jsonPath("$.total").value(60.0));

        publishListing(listed, 4, "ARCHIVED", "20.00");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> !prices.find(List.of(listed)).getFirst().purchasable());
        checkout(buyer(buyer), "priced-2", items(listed, 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
    }

    // --- helpers -------------------------------------------------------------------------------

    private void price(String sku, String amount, String currency, boolean purchasable, long version) {
        prices.apply(new ProductPrice(sku, "prod-" + sku, "acme", Map.of("en", "Item"), new BigDecimal(amount),
                currency, purchasable, version));
    }

    private void publishListing(String sku, long version, String status, String amount) throws Exception {
        var event = Map.of(
                "eventType", "ProductChanged",
                "productId", "prod-" + sku,
                "sku", sku,
                "sellerId", "acme",
                "title", Map.of("en", "Phone", "ar", "هاتف"),
                "price", Map.of("amount", new BigDecimal(amount), "currency", "AED"),
                "status", status,
                "version", version);
        kafka.send("catalog.products.v1", "prod-" + sku, json.writeValueAsString(event)).get();
    }

    private static String items(String sku, int quantity) {
        return """
                {"items": [{"sku": "%s", "quantity": %d}], "paymentMethod": "pm_card_visa"}"""
                .formatted(sku, quantity);
    }

    private ResultActions checkout(RequestPostProcessor caller, String key, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                .with(caller).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
