package io.souqly.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.souqly.order.order.Order;
import io.souqly.order.order.OrderLine;
import io.souqly.order.order.OrderStatus;
import io.souqly.order.order.StatusChange;
import io.souqly.order.pricing.PriceBook;
import io.souqly.order.pricing.ProductPrice;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The checkout saga against a scripted inventory and PSP: the happy path, every refusal and its
 * compensation, timeouts and outages that must be retried rather than guessed, and recovery of
 * an order whose instance died mid-checkout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CheckoutSagaIT {

    static final String RESERVATIONS = "/inventory/api/v1/reservations";
    static final String CHARGES = "/psp/v1/payment_intents";
    static final String REFUNDS = "/psp/v1/refunds";

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    WireMockServer fakes;

    @Autowired
    PriceBook prices;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    KafkaContainer kafkaContainer;

    String buyer;
    String sku;

    @BeforeEach
    void freshFakes() {
        fakes.resetAll();
        fakes.stubFor(post("/token").willReturn(okJson("""
                {"access_token": "service-token", "token_type": "Bearer", "expires_in": 300}""")));
        buyer = "buyer-" + UUID.randomUUID();
        sku = "PHONE-" + UUID.randomUUID();
        price(sku, "1499.50", "AED", true);
    }

    @Test
    void reservesPaysAndConfirms() throws Exception {
        inventoryReserves("res-1");
        pspCharges("pi_1");
        inventoryConfirms("res-1");

        JsonNode order = body(checkout(buyer, "key-1", sku, 2)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/orders/")))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.total").value(2999.0))
                .andExpect(jsonPath("$.currency").value("AED"))
                .andExpect(jsonPath("$.lines[0].sellerId").value("acme"))
                .andExpect(jsonPath("$.lines[0].title.en").value("Phone"))
                .andReturn());
        String id = order.get("id").asString();
        assertThat(statuses(order)).containsExactly("PLACED", "STOCK_RESERVED", "PAID", "CONFIRMED");

        // Inventory was called with this service's own token, keyed by the order.
        fakes.verify(postRequestedFor(urlPathEqualTo(RESERVATIONS))
                .withHeader("Authorization", equalTo("Bearer service-token"))
                .withRequestBody(equalToJson("""
                        {"orderId": "%s", "lines": [{"sku": "%s", "quantity": 2}]}""".formatted(id, sku))));
        // The PSP got minor units and an idempotency key derived from the order.
        fakes.verify(postRequestedFor(urlPathEqualTo(CHARGES))
                .withHeader("Idempotency-Key", equalTo("order-" + id))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("299900")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("aed")))
                .withRequestBody(matchingJsonPath("$.metadata.order_id", equalTo(id))));
        // The payment-method token is not kept once it has been used.
        assertThat(mongo.findById(id, Order.class).paymentMethod()).isNull();
    }

    @Test
    void publishesEachStepAsAnEvent() throws Exception {
        inventoryReserves("res-2");
        pspCharges("pi_2");
        inventoryConfirms("res-2");
        String id = body(checkout(buyer, "key-1", sku, 1).andExpect(status().isCreated()).andReturn())
                .get("id").asString();

        List<String> types = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("orders.order-events.v1"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (id.equals(record.key())) {
                        types.add(json.readTree(record.value()).get("eventType").asString());
                    }
                });
                return types.size() >= 4;
            });
        }
        assertThat(types).containsExactly("OrderPlaced", "OrderStockReserved", "OrderPaid", "OrderConfirmed");
    }

    @Test
    void outOfStockRejectsWithoutCharging() throws Exception {
        fakes.stubFor(post(RESERVATIONS).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("""
                        {"code": "INSUFFICIENT_STOCK", "detail": "Only 1 left"}""")));

        checkout(buyer, "key-1", sku, 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.failure.code").value("OUT_OF_STOCK"));
        fakes.verify(exactly(0), postRequestedFor(urlPathEqualTo(CHARGES)));
    }

    @Test
    void declinedPaymentReleasesTheStock() throws Exception {
        inventoryReserves("res-3");
        fakes.stubFor(post(CHARGES).willReturn(aResponse().withStatus(402).withHeader("Content-Type",
                "application/json").withBody("""
                {"error": {"code": "card_declined", "message": "Your card was declined."}}""")));
        fakes.stubFor(post(RESERVATIONS + "/res-3/release").willReturn(okJson("""
                {"id": "res-3", "status": "RELEASED"}""")));

        JsonNode order = body(checkout(buyer, "key-1", sku, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failure.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.failure.message").value("Your card was declined."))
                .andReturn());
        assertThat(statuses(order)).containsExactly("PLACED", "STOCK_RESERVED", "RELEASING_STOCK", "CANCELLED");
        fakes.verify(exactly(1), postRequestedFor(urlPathEqualTo(RESERVATIONS + "/res-3/release")));
        fakes.verify(exactly(0), postRequestedFor(urlPathMatching(".*/confirm")));
    }

    @Test
    void aPaymentTimeoutIsRetriedWithTheSameKeyNotTreatedAsADecline() throws Exception {
        inventoryReserves("res-4");
        inventoryConfirms("res-4");
        // The first charge takes longer than our timeout; the PSP may or may not have charged.
        fakes.stubFor(post(CHARGES).inScenario("slow-psp").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("""
                        {"id": "pi_4", "status": "succeeded"}""").withFixedDelay(1_500))
                .willSetStateTo("answered"));
        fakes.stubFor(post(CHARGES).inScenario("slow-psp").whenScenarioStateIs("answered")
                .willReturn(okJson("""
                        {"id": "pi_4", "status": "succeeded"}""")));

        String id = body(checkout(buyer, "key-1", sku, 1)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STOCK_RESERVED"))
                .andExpect(jsonPath("$.completed").value(false))
                .andReturn()).get("id").asString();

        awaitStatus(id, "CONFIRMED");
        fakes.verify(exactly(2), postRequestedFor(urlPathEqualTo(CHARGES))
                .withHeader("Idempotency-Key", equalTo("order-" + id)));
        fakes.verify(exactly(1), postRequestedFor(urlPathEqualTo(RESERVATIONS)));
    }

    @Test
    void aReservationThatExpiredBeforeConfirmationIsRefunded() throws Exception {
        inventoryReserves("res-5");
        pspCharges("pi_5");
        fakes.stubFor(post(RESERVATIONS + "/res-5/confirm").willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json").withBody("""
                        {"code": "RESERVATION_EXPIRED", "detail": "Reservation res-5 expired"}""")));
        fakes.stubFor(post(REFUNDS).willReturn(okJson("""
                {"id": "re_5", "status": "succeeded"}""")));

        JsonNode order = body(checkout(buyer, "key-1", sku, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.failure.code").value("RESERVATION_EXPIRED"))
                .andReturn());
        String id = order.get("id").asString();
        assertThat(statuses(order)).containsExactly("PLACED", "STOCK_RESERVED", "PAID", "REFUNDING", "CANCELLED");
        fakes.verify(exactly(1), postRequestedFor(urlPathEqualTo(REFUNDS))
                .withHeader("Idempotency-Key", equalTo("refund-order-" + id))
                .withRequestBody(equalToJson("""
                        {"payment_intent": "pi_5"}""")));
    }

    @Test
    void anInventoryOutageDelaysTheOrderInsteadOfFailingIt() throws Exception {
        fakes.stubFor(post(RESERVATIONS).inScenario("inventory-down").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        fakes.stubFor(post(RESERVATIONS).inScenario("inventory-down").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id": "res-6", "status": "PENDING"}""")));
        pspCharges("pi_6");
        inventoryConfirms("res-6");

        String id = body(checkout(buyer, "key-1", sku, 1)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andReturn()).get("id").asString();

        awaitStatus(id, "CONFIRMED");
    }

    @Test
    void anOrderThatKeepsFailingIsHandedToAPerson() throws Exception {
        fakes.stubFor(post(RESERVATIONS).willReturn(aResponse().withStatus(500)));

        String id = body(checkout(buyer, "key-1", sku, 1).andExpect(status().isAccepted()).andReturn())
                .get("id").asString();

        JsonNode order = awaitStatus(id, "NEEDS_ATTENTION");
        assertThat(order.at("/failure/message").asString()).contains("gave up after 4 attempts");
        fakes.verify(exactly(4), postRequestedFor(urlPathEqualTo(RESERVATIONS)));
    }

    @Test
    void anOrderAbandonedMidCheckoutIsFinishedByRecovery() throws Exception {
        // An instance took the payment, then died holding the lease, before confirming the stock.
        Instant past = Instant.now().minusSeconds(60);
        String id = UUID.randomUUID().toString();
        mongo.insert(new Order(id, buyer, "key-1", "hash",
                List.of(new OrderLine(sku, "prod-1", "acme", Map.of("en", "Phone"), 1, new BigDecimal("1499.50"))),
                new BigDecimal("1499.50"), "AED", null, "res-7", "pi_7", OrderStatus.PAID, null,
                List.of(new StatusChange(OrderStatus.PAID, past, "payment captured")),
                0, null, past, past.plusSeconds(30), 3, past, past));
        inventoryConfirms("res-7");

        awaitStatus(id, "CONFIRMED");
        fakes.verify(exactly(0), postRequestedFor(urlPathEqualTo(CHARGES)));
    }

    @Test
    void anOrderLeasedByALiveInstanceIsLeftAlone() throws Exception {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        mongo.insert(new Order(id, buyer, "key-1", "hash",
                List.of(new OrderLine(sku, "prod-1", "acme", Map.of("en", "Phone"), 1, new BigDecimal("1499.50"))),
                new BigDecimal("1499.50"), "AED", null, "res-8", "pi_8", OrderStatus.PAID, null,
                List.of(new StatusChange(OrderStatus.PAID, now, "payment captured")),
                0, null, now.minusSeconds(1), now.plusSeconds(30), 3, now, now));
        inventoryConfirms("res-8");

        Thread.sleep(1_000);
        assertThat(mongo.findById(id, Order.class).status()).isEqualTo(OrderStatus.PAID);
        fakes.verify(exactly(0), postRequestedFor(urlPathMatching(".*/confirm")));
    }

    // --- helpers -------------------------------------------------------------------------------

    private void price(String sku, String amount, String currency, boolean purchasable) {
        prices.apply(new ProductPrice(sku, "prod-" + sku, "acme", Map.of("en", "Phone", "ar", "هاتف"),
                new BigDecimal(amount), currency, purchasable, 1));
    }

    private void inventoryReserves(String reservationId) {
        fakes.stubFor(post(RESERVATIONS).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"id": "%s", "status": "PENDING"}""".formatted(reservationId))));
    }

    private void inventoryConfirms(String reservationId) {
        fakes.stubFor(post(RESERVATIONS + "/" + reservationId + "/confirm").willReturn(okJson("""
                {"id": "%s", "status": "CONFIRMED"}""".formatted(reservationId))));
    }

    private void pspCharges(String paymentId) {
        fakes.stubFor(post(CHARGES).willReturn(okJson("""
                {"id": "%s", "status": "succeeded"}""".formatted(paymentId))));
    }

    private org.springframework.test.web.servlet.ResultActions checkout(String buyer, String key, String sku,
            int quantity) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                .with(buyer(buyer)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"items": [{"sku": "%s", "quantity": %d}], "paymentMethod": "pm_card_visa"}"""
                        .formatted(sku, quantity)));
    }

    private JsonNode awaitStatus(String id, String expected) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            last[0] = body(mvc.perform(get("/api/v1/orders/{id}", id).with(buyer(buyer)))
                    .andExpect(status().isOk()).andReturn());
            assertThat(last[0].get("status").asString()).isEqualTo(expected);
        });
        return last[0];
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> statuses(JsonNode order) {
        List<String> statuses = new ArrayList<>();
        order.get("history").forEach(change -> statuses.add(change.get("status").asString()));
        return statuses;
    }

    static JwtRequestPostProcessor buyer(String subject) {
        return jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("order:place"));
    }
}
