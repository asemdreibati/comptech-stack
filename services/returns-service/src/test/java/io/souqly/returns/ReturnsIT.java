package io.souqly.returns;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.souqly.returns.orders.OrderReplica;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Returns end to end on the real engine: the policy, seller decisions and their deadline, disputes,
 * the parcel that never comes, inspection, refunds that fail and recover, and the rules that stop a
 * buyer returning more than they bought. Timers are fired by executing their jobs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReturnsIT {

    static final String REFUNDS = "/v1/refunds";
    static final JwtRequestPostProcessor OPS = jwt().jwt(t -> t.subject("ops-1"))
            .authorities(new SimpleGrantedAuthority("return:arbitrate"));
    static final JwtRequestPostProcessor WAREHOUSE = jwt().jwt(t -> t.subject("warehouse-1"))
            .authorities(new SimpleGrantedAuthority("return:handle"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    OrderReplica orders;

    @Autowired
    WireMockServer psp;

    @Autowired
    ManagementService management;

    @Autowired
    RuntimeService runtime;

    String buyerId;
    JwtRequestPostProcessor buyer;
    String sellerId;
    JwtRequestPostProcessor seller;

    @BeforeEach
    void freshParties() {
        psp.resetAll();
        psp.stubFor(post(REFUNDS).willReturn(okJson("""
                {"id": "re_test", "status": "succeeded"}""")));
        buyerId = "buyer-" + UUID.randomUUID();
        buyer = jwt().jwt(t -> t.subject(buyerId)).authorities(new SimpleGrantedAuthority("return:request"));
        sellerId = "shop-" + UUID.randomUUID().toString().substring(0, 8);
        seller = seller(sellerId);
    }

    @Test
    void aSmallReturnIsApprovedByPolicyAndRefundedAfterInspection() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("CABLE-1", sellerId, 2, "40.00"));

        String id = requestReturn("r-1", orderId, "CHANGED_MIND", item("CABLE-1", 2))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.refundAmount").value(80.0))
                .andReturn().getResponse().getContentAsString();
        String returnId = json.readTree(id).path("id").asString();

        awaitStatus(returnId, "APPROVED");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/returns/{id}/received", returnId).with(WAREHOUSE))
                .andExpect(status().isOk());
        awaitStatus(returnId, "RECEIVED");
        inspect(returnId, "PASS", null).andExpect(status().isOk());
        awaitStatus(returnId, "REFUNDED");

        // A partial refund in minor units, keyed by the return so a retry cannot refund twice.
        psp.verify(exactly(1), postRequestedFor(urlPathEqualTo(REFUNDS))
                .withHeader("Idempotency-Key", equalTo("return-" + returnId))
                .withRequestBody(equalToJson("""
                        {"payment_intent": "pi_%s", "amount": 8000}""".formatted(orderId))));
        mvc.perform(get("/api/v1/returns/{id}", returnId).with(buyer))
                .andExpect(jsonPath("$.refundId").value("re_test"))
                .andExpect(jsonPath("$.decisions[0].stage").value("RECEIPT"))
                .andExpect(jsonPath("$.decisions[1].stage").value("INSPECTION"));
        assertThat(eventTypes(returnId, 3)).containsExactly("ReturnApproved", "ReturnReceived", "ReturnRefunded");
    }

    @Test
    void theSellerDecidesLargerReturnsAndOnlyThatSellerCan() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("PHONE-1", sellerId, 1, "1499.50"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "CHANGED_MIND", item("PHONE-1", 1)));
        awaitStatus(returnId, "AWAITING_SELLER");

        // Another seller neither sees the return nor can decide it.
        JwtRequestPostProcessor other = seller("other-" + UUID.randomUUID().toString().substring(0, 6));
        assertThat(queue(other)).noneMatch(item -> item.path("returnRequest").path("id").asString().equals(returnId));
        sellerDecision(other, returnId, "APPROVE", null).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/returns/{id}", returnId).with(other)).andExpect(status().isNotFound());

        assertThat(queue(seller)).anyMatch(item -> item.path("returnRequest").path("id").asString().equals(returnId)
                && item.path("stage").asString().equals("SELLER_DECISION"));
        sellerDecision(seller, returnId, "REJECT", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        sellerDecision(seller, returnId, "APPROVE", null).andExpect(status().isOk());
        awaitStatus(returnId, "APPROVED");
    }

    @Test
    void aSellerWhoDoesNotAnswerInTimeHasTheReturnApproved() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("PHONE-1", sellerId, 1, "1499.50"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "NOT_AS_DESCRIBED", item("PHONE-1", 1)));
        awaitStatus(returnId, "AWAITING_SELLER");

        fireTimer(returnId, "Timer_SellerSla");

        awaitStatus(returnId, "APPROVED");
        mvc.perform(get("/api/v1/returns/{id}", returnId).with(buyer))
                .andExpect(jsonPath("$.decisions[0].actorId").value("system"))
                .andExpect(jsonPath("$.decisions[0].note").value("No answer from the seller in time"));
    }

    @Test
    void aRejectedBuyerCanDisputeAndOperationsDecide() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("PHONE-1", sellerId, 1, "1499.50"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "NOT_AS_DESCRIBED", item("PHONE-1", 1)));
        awaitStatus(returnId, "AWAITING_SELLER");
        sellerDecision(seller, returnId, "REJECT", "Item shows signs of use").andExpect(status().isOk());
        awaitStatus(returnId, "SELLER_REJECTED");

        // Only the buyer can answer, with a reason when disputing.
        respond(jwt().jwt(t -> t.subject("someone-else")).authorities(new SimpleGrantedAuthority("return:request")),
                returnId, "DISPUTE", "Not mine").andExpect(status().isNotFound());
        respond(buyer, returnId, "DISPUTE", "It arrived with a cracked screen").andExpect(status().isOk());
        awaitStatus(returnId, "IN_DISPUTE");

        assertThat(queue(OPS)).anyMatch(item -> item.path("returnRequest").path("id").asString().equals(returnId)
                && item.path("stage").asString().equals("ARBITRATION"));
        arbitrate(returnId, "APPROVE", "Delivery photos show the damage").andExpect(status().isOk());
        awaitStatus(returnId, "APPROVED");

        mvc.perform(get("/api/v1/returns/{id}", returnId).with(seller))
                .andExpect(jsonPath("$.decisions[*].stage").value(
                        contains("SELLER_DECISION", "BUYER_RESPONSE", "ARBITRATION")));
    }

    @Test
    void aBuyerWhoDoesNotDisputeInTimeAcceptsTheRejection() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("PHONE-1", sellerId, 1, "1499.50"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "CHANGED_MIND", item("PHONE-1", 1)));
        awaitStatus(returnId, "AWAITING_SELLER");
        sellerDecision(seller, returnId, "REJECT", "Outside our policy for opened items").andExpect(status().isOk());
        awaitStatus(returnId, "SELLER_REJECTED");

        fireTimer(returnId, "Timer_DisputeWindow");

        awaitStatus(returnId, "REJECTED");
        // A rejected return frees the items for another attempt.
        requestReturn("r-2", orderId, "DAMAGED", item("PHONE-1", 1)).andExpect(status().isAccepted());
    }

    @Test
    void aParcelThatNeverArrivesCancelsTheReturn() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("CABLE-1", sellerId, 1, "40.00"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "CHANGED_MIND", item("CABLE-1", 1)));
        awaitStatus(returnId, "APPROVED");

        fireTimer(returnId, "Timer_ShippingWindow");

        awaitStatus(returnId, "CANCELLED");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/returns/{id}/received", returnId).with(WAREHOUSE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_AWAITING_PARCEL"));
    }

    @Test
    void aFailedInspectionRejectsWithoutRefunding() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("CABLE-1", sellerId, 1, "40.00"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "DAMAGED", item("CABLE-1", 1)));
        awaitStatus(returnId, "APPROVED");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/returns/{id}/received", returnId).with(WAREHOUSE)).andExpect(status().isOk());
        awaitStatus(returnId, "RECEIVED");

        inspect(returnId, "FAIL", null).andExpect(status().isUnprocessableContent());
        inspect(returnId, "FAIL", "Box contains a different item").andExpect(status().isOk());

        awaitStatus(returnId, "REJECTED");
        psp.verify(exactly(0), postRequestedFor(urlPathEqualTo(REFUNDS)));
    }

    @Test
    void aRefundThatKeepsFailingRaisesAnIncidentAndCanBeRetried() throws Exception {
        psp.resetAll();
        psp.stubFor(post(REFUNDS).willReturn(aResponse().withStatus(503)));
        String orderId = confirmedOrder(Instant.now(), line("CABLE-1", sellerId, 1, "40.00"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "CHANGED_MIND", item("CABLE-1", 1)));
        awaitStatus(returnId, "APPROVED");
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/returns/{id}/received", returnId).with(WAREHOUSE)).andExpect(status().isOk());
        awaitStatus(returnId, "RECEIVED");
        inspect(returnId, "PASS", null).andExpect(status().isOk());

        String processInstance = runtime.createProcessInstanceQuery().processInstanceBusinessKey(returnId)
                .singleResult().getId();
        await().atMost(Duration.ofSeconds(30)).until(() ->
                runtime.createIncidentQuery().processInstanceId(processInstance).count() == 1);
        assertThat(runtime.createIncidentQuery().processInstanceId(processInstance).singleResult().getActivityId())
                .isEqualTo("Task_Refund");
        mvc.perform(get("/api/v1/returns/{id}", returnId).with(buyer)).andExpect(jsonPath("$.status").value("RECEIVED"));

        // The PSP recovers; operations retry the failed job, as they would from the engine's cockpit.
        psp.resetAll();
        psp.stubFor(post(REFUNDS).willReturn(okJson("""
                {"id": "re_recovered", "status": "succeeded"}""")));
        var failed = management.createJobQuery().processInstanceId(processInstance).withException().singleResult();
        management.setJobRetries(failed.getId(), 1);

        awaitStatus(returnId, "REFUNDED");
        psp.verify(postRequestedFor(urlPathEqualTo(REFUNDS)).withHeader("Idempotency-Key", equalTo("return-" + returnId)));
    }

    @Test
    void buyersCannotReturnMoreThanTheyBoughtOrOutsideTheRules() throws Exception {
        String otherSeller = "shop-" + UUID.randomUUID().toString().substring(0, 8);
        String orderId = confirmedOrder(Instant.now(),
                line("PHONE-1", sellerId, 2, "1499.50"), line("CASE-1", otherSeller, 1, "49.00"));

        requestReturn("r-1", orderId, "CHANGED_MIND", item("PHONE-1", 1)).andExpect(status().isAccepted());
        requestReturn("r-2", orderId, "CHANGED_MIND", item("PHONE-1", 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value("Only 1 of PHONE-1 can still be returned"));
        requestReturn("r-3", orderId, "CHANGED_MIND", item("PHONE-1", 1), item("CASE-1", 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MIXED_SELLERS"));
        requestReturn("r-4", orderId, "CHANGED_MIND", item("TV-1", 1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_ORDER"));

        // Someone else's order does not exist for this buyer.
        var stranger = jwt().jwt(t -> t.subject("stranger")).authorities(new SimpleGrantedAuthority("return:request"));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/returns")
                        .with(stranger).header("Idempotency-Key", "s-1").contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderId, "CHANGED_MIND", item("CASE-1", 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        String oldOrder = confirmedOrder(Instant.now().minus(Duration.ofDays(15)), line("CABLE-1", sellerId, 1, "40.00"));
        requestReturn("r-5", oldOrder, "DAMAGED", item("CABLE-1", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_WINDOW_CLOSED"));
    }

    @Test
    void concurrentRequestsCannotTogetherReturnMoreThanWasBought() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("PHONE-1", sellerId, 2, "1499.50"));

        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String key = "race-" + i;
            attempts.add(() -> requestReturn(key, orderId, "CHANGED_MIND", item("PHONE-1", 2))
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (var result : pool.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }
        assertThat(statuses).containsOnlyOnce(202).containsOnly(202, 409);
    }

    @Test
    void retryingARequestReturnsTheSameReturn() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("CABLE-1", sellerId, 3, "40.00"));
        String returnId = returnIdOf(requestReturn("same-key", orderId, "CHANGED_MIND", item("CABLE-1", 1)));

        requestReturn("same-key", orderId, "CHANGED_MIND", item("CABLE-1", 1))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(returnId));
        requestReturn("same-key", orderId, "CHANGED_MIND", item("CABLE-1", 2))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void eachPartyOnlyReachesItsOwnStage() throws Exception {
        String orderId = confirmedOrder(Instant.now(), line("PHONE-1", sellerId, 1, "1499.50"));
        String returnId = returnIdOf(requestReturn("r-1", orderId, "CHANGED_MIND", item("PHONE-1", 1)));
        awaitStatus(returnId, "AWAITING_SELLER");

        sellerDecision(buyer, returnId, "APPROVE", null).andExpect(status().isForbidden());
        sellerDecision(WAREHOUSE, returnId, "APPROVE", null).andExpect(status().isForbidden());
        arbitrate(returnId, "APPROVE", "too early").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/return-tasks").with(buyer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/returns/{id}", returnId).with(WAREHOUSE)).andExpect(status().isOk());
    }

    // --- helpers -------------------------------------------------------------------------------

    static JwtRequestPostProcessor seller(String sellerId) {
        return jwt().jwt(t -> t.subject("user-" + sellerId).claim("seller_id", sellerId))
                .authorities(new SimpleGrantedAuthority("return:decide"));
    }

    static Map<String, Object> line(String sku, String sellerId, int quantity, String unitPrice) {
        return Map.of("sku", sku, "productId", "prod-" + sku, "sellerId", sellerId, "title", Map.of("en", sku),
                "quantity", quantity, "unitPrice", new BigDecimal(unitPrice));
    }

    static Map<String, Object> item(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    /** Publishes an OrderConfirmed event the way the order service does, and waits for the replica. */
    @SafeVarargs
    private String confirmedOrder(Instant confirmedAt, Map<String, Object>... lines) throws Exception {
        String orderId = UUID.randomUUID().toString();
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "OrderConfirmed");
        event.put("occurredAt", confirmedAt.toString());
        event.put("orderId", orderId);
        event.put("buyerId", buyerId);
        event.put("status", "CONFIRMED");
        event.put("lines", List.of(lines));
        event.put("currency", "AED");
        event.put("paymentId", "pi_" + orderId);
        event.put("version", 4);
        kafka.send("orders.order-events.v1", orderId, json.writeValueAsString(event)).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> orders.find(orderId).isPresent());
        return orderId;
    }

    private String body(String orderId, String reason, Map<?, ?>... items) {
        return json.writeValueAsString(Map.of("orderId", orderId, "reason", reason, "items", List.of(items)));
    }

    private ResultActions requestReturn(String key, String orderId, String reason, Map<?, ?>... items)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/returns")
                .with(buyer).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body(orderId, reason, items)));
    }

    private String returnIdOf(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .path("id").asString();
    }

    private ResultActions sellerDecision(JwtRequestPostProcessor caller, String returnId, String decision, String note)
            throws Exception {
        return postJson(caller, "/api/v1/returns/{id}/seller-decision", returnId, "decision", decision, note);
    }

    private ResultActions respond(JwtRequestPostProcessor caller, String returnId, String response, String note)
            throws Exception {
        return postJson(caller, "/api/v1/returns/{id}/response", returnId, "response", response, note);
    }

    private ResultActions arbitrate(String returnId, String decision, String note) throws Exception {
        return postJson(OPS, "/api/v1/returns/{id}/arbitration", returnId, "decision", decision, note);
    }

    private ResultActions inspect(String returnId, String result, String note) throws Exception {
        return postJson(WAREHOUSE, "/api/v1/returns/{id}/inspection", returnId, "result", result, note);
    }

    private ResultActions postJson(JwtRequestPostProcessor caller, String path, String returnId, String field,
            String value, String note) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put(field, value);
        body.put("note", note);
        return mvc.perform(MockMvcRequestBuilders.post(path, returnId)
                .with(caller).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private List<JsonNode> queue(JwtRequestPostProcessor caller) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        json.readTree(mvc.perform(get("/api/v1/return-tasks").with(caller)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).forEach(items::add);
        return items;
    }

    private void awaitStatus(String returnId, String expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/returns/{id}", returnId).with(buyer))
                        .andExpect(jsonPath("$.status").value(expected)));
    }

    private void fireTimer(String returnId, String timerId) {
        String processInstance = runtime.createProcessInstanceQuery().processInstanceBusinessKey(returnId)
                .singleResult().getId();
        var timer = management.createJobQuery().processInstanceId(processInstance).timers().activityId(timerId)
                .singleResult();
        assertThat(timer).as("timer %s", timerId).isNotNull();
        management.executeJob(timer.getId());
    }

    private List<String> eventTypes(String returnId, int expected) {
        List<String> types = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("returns.return-requests.v1"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (returnId.equals(record.key())) {
                        types.add(json.readTree(record.value()).path("eventType").asString());
                    }
                });
                return types.size() >= expected;
            });
        }
        return types;
    }
}
