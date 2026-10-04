package io.souqly.seller;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.souqly.seller.workflow.Onboarding;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.cibseven.bpm.engine.ManagementService;
import org.cibseven.bpm.engine.RuntimeService;
import org.cibseven.bpm.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seller onboarding workflow end to end: real engine, database, form validation, object
 * storage, Kafka and Keycloak. Timers are fired by executing their jobs rather than waiting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OnboardingIT {

    static final byte[] PDF = "%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    ManagementService management;

    @Autowired
    RuntimeService runtime;

    @Autowired
    TaskService tasks;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    @Qualifier("keycloak")
    GenericContainer<?> keycloak;

    @Test
    void anApprovedApplicantSignsInAsASeller() throws Exception {
        var applicant = newKeycloakUser();
        String handle = handle();
        String iban = iban();
        String id = createApplication(applicant.caller(), handle, kyc(iban, licence()));
        uploadAllDocuments(applicant.caller(), id);
        submit(applicant.caller(), id).andExpect(jsonPath("$.status").value("SUBMITTED"));

        var review = awaitReviewTask(complianceOfficer("officer-1"), id);
        assertThat(review.path("riskTier").asString()).isEqualTo("LOW");
        assertThat(review.path("stage").asString()).isEqualTo("REVIEW");
        decide(complianceOfficer("officer-1"), review.path("taskId").asString(), "APPROVE", null)
                .andExpect(status().isOk());

        awaitStatus(applicant.caller(), id, "APPROVED");

        // The applicant's next sign-in carries the seller identity and a seller's permissions.
        JsonNode claims = claims(userToken(applicant.username(), applicant.password()));
        assertThat(claims.path("seller_id").asString()).isEqualTo(handle);
        assertThat(claims.path("realm_access").path("roles").toString()).contains("\"seller\"", "\"buyer\"");
        assertThat(claims.path("resource_access").path("catalog-service").path("roles").toString())
                .contains("product:write");

        // Events announce each status change, without raw bank or phone details.
        List<JsonNode> events = eventsFor(id, 2);
        assertThat(events).extracting(event -> event.path("eventType").asString())
                .containsExactly("SellerApplicationInReview", "SellerApproved");
        JsonNode approved = events.get(1);
        assertThat(approved.path("sellerId").asString()).isEqualTo(handle);
        assertThat(approved.path("identifiers").path("iban").asString()).hasSize(64);
        assertThat(approved.toString()).doesNotContain(iban, "+971501234567");

        // Approved sellers cannot apply again.
        mvc.perform(post("/api/v1/applications").with(applicant.caller()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle(), kyc())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_EXISTS"));
    }

    @Test
    void reviewersCanAskForMoreInformationAndApplicantsSeeWhy() throws Exception {
        var applicant = applicant();
        String id = createApplication(applicant, handle(), kyc());
        uploadAllDocuments(applicant, id);
        submit(applicant, id);

        String task = awaitReviewTask(complianceOfficer("officer-2"), id).path("taskId").asString();
        decide(complianceOfficer("officer-2"), task, "MORE_INFO", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        decide(complianceOfficer("officer-2"), task, "MORE_INFO", "The bank letter does not show the IBAN")
                .andExpect(status().isOk());
        awaitStatus(applicant, id, "INFORMATION_REQUESTED");

        // The applicant sees the reason but not who gave it, fixes the details and a document, and resubmits.
        mvc.perform(get("/api/v1/applications/{id}", id).with(applicant))
                .andExpect(jsonPath("$.decisions[0].note").value("The bank letter does not show the IBAN"))
                .andExpect(jsonPath("$.decisions[0].reviewerId").doesNotExist());
        Map<String, Object> corrected = new LinkedHashMap<>(kyc());
        corrected.put("address", "Office 12, Business Bay, Dubai, United Arab Emirates");
        String handle = json.readTree(mvc.perform(get("/api/v1/applications/{id}", id).with(applicant))
                .andReturn().getResponse().getContentAsString()).path("sellerId").asString();
        mvc.perform(put("/api/v1/applications/{id}", id).with(applicant).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle, corrected)))
                .andExpect(status().isOk());
        upload(applicant, id, "BANK_LETTER", "application/pdf", PDF).andExpect(status().isOk());
        submit(applicant, id);

        // Back in review, screened again; this time it is rejected.
        String again = awaitReviewTask(complianceOfficer("officer-3"), id).path("taskId").asString();
        assertThat(again).isNotEqualTo(task);
        decide(complianceOfficer("officer-3"), again, "REJECT", "Trade licence is expired").andExpect(status().isOk());
        awaitStatus(applicant, id, "REJECTED");

        // Reviewers see the whole audit trail, with who decided what.
        mvc.perform(get("/api/v1/applications/{id}", id).with(complianceOfficer("officer-9")))
                .andExpect(jsonPath("$.decisions.length()").value(2))
                .andExpect(jsonPath("$.decisions[0].reviewerId").value("officer-2"))
                .andExpect(jsonPath("$.decisions[1].decision").value("REJECT"));
        assertThat(eventsFor(id, 4)).extracting(event -> event.path("eventType").asString()).containsExactly(
                "SellerApplicationInReview", "SellerApplicationInformationRequested", "SellerApplicationInReview",
                "SellerApplicationRejected");
    }

    @Test
    void anApplicantSharingABankAccountNeedsTwoDifferentReviewers() throws Exception {
        String sharedIban = iban();
        var first = applicant();
        String firstId = createApplication(first, handle(), kyc(sharedIban, licence()));
        uploadAllDocuments(first, firstId);
        submit(first, firstId).andExpect(status().isAccepted());

        var second = newKeycloakUser();
        String handle = handle();
        String secondId = createApplication(second.caller(), handle, kyc(sharedIban, licence()));
        uploadAllDocuments(second.caller(), secondId);
        submit(second.caller(), secondId);

        JsonNode review = awaitReviewTask(complianceLead("lead-1"), secondId);
        assertThat(review.path("riskTier").asString()).isEqualTo("HIGH");
        assertThat(review.path("sharedIdentifiers").asInt()).isEqualTo(1);
        decide(complianceLead("lead-1"), review.path("taskId").asString(), "APPROVE", null).andExpect(status().isOk());

        JsonNode secondReview = awaitReviewTask(complianceLead("lead-1"), secondId, "SECOND_REVIEW");
        assertThat(secondReview.path("firstReviewer").asString()).isEqualTo("lead-1");
        // Compliance officers do not even see second reviews.
        assertThat(queue(complianceOfficer("officer-4"))).noneMatch(item ->
                item.path("taskId").asString().equals(secondReview.path("taskId").asString()));
        // The first reviewer cannot also be the second.
        decide(complianceLead("lead-1"), secondReview.path("taskId").asString(), "APPROVE", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FOUR_EYES_REQUIRED"));
        decide(complianceLead("lead-2"), secondReview.path("taskId").asString(), "APPROVE", null)
                .andExpect(status().isOk());

        awaitStatus(second.caller(), secondId, "APPROVED");
        assertThat(claims(userToken(second.username(), second.password())).path("seller_id").asString())
                .isEqualTo(handle);
    }

    @Test
    void aReviewThatMissesItsSlaIsEscalated() throws Exception {
        var applicant = newKeycloakUser().caller();
        String id = createApplication(applicant, handle(), kyc());
        uploadAllDocuments(applicant, id);
        submit(applicant, id);
        String task = awaitReviewTask(complianceOfficer("officer-5"), id).path("taskId").asString();
        assertThat(queue(complianceLead("lead-3"))).anyMatch(item -> item.path("taskId").asString().equals(task));

        fireTimer(id, "Timer_ReviewSla");

        JsonNode escalated = queue(complianceLead("lead-3")).stream()
                .filter(item -> item.path("taskId").asString().equals(task)).findFirst().orElseThrow();
        assertThat(escalated.path("priority").asInt()).isEqualTo(Onboarding.ESCALATED_PRIORITY);
        assertThat(escalated.path("escalated").asBoolean()).isTrue();
        assertThat(tasks.getIdentityLinksForTask(task)).extracting(link -> link.getGroupId())
                .contains("compliance", "compliance-leads");
        // The escalation is non-interrupting: the officer can still decide the same task.
        decide(complianceOfficer("officer-5"), task, "APPROVE", null).andExpect(status().isOk());
        awaitStatus(applicant, id, "APPROVED");
    }

    @Test
    void aSellerRoleThatCannotBeGrantedRaisesAnIncidentInsteadOfApproving() throws Exception {
        // Not a Keycloak user, so the grant fails on every retry.
        var applicant = applicant();
        String id = createApplication(applicant, handle(), kyc());
        uploadAllDocuments(applicant, id);
        submit(applicant, id);
        String task = awaitReviewTask(complianceOfficer("officer-10"), id).path("taskId").asString();
        decide(complianceOfficer("officer-10"), task, "APPROVE", null).andExpect(status().isOk());

        String processInstance = runtime.createProcessInstanceQuery().processInstanceBusinessKey(id)
                .singleResult().getId();
        await().atMost(Duration.ofSeconds(30)).until(() ->
                runtime.createIncidentQuery().processInstanceId(processInstance).count() == 1);
        var incident = runtime.createIncidentQuery().processInstanceId(processInstance).singleResult();
        assertThat(incident.getActivityId()).isEqualTo("Task_Provision");
        // Operations can see and retry it; meanwhile nothing claims the seller was approved.
        mvc.perform(get("/api/v1/applications/{id}", id).with(applicant)).andExpect(jsonPath("$.status").value("IN_REVIEW"));
        assertThat(eventsFor(id, 1)).extracting(event -> event.path("eventType").asString())
                .doesNotContain("SellerApproved");
    }

    @Test
    void anUnansweredRequestForInformationExpiresTheApplication() throws Exception {
        var applicant = applicant();
        String handle = handle();
        String id = createApplication(applicant, handle, kyc());
        uploadAllDocuments(applicant, id);
        submit(applicant, id);
        String task = awaitReviewTask(complianceOfficer("officer-6"), id).path("taskId").asString();
        decide(complianceOfficer("officer-6"), task, "MORE_INFO", "Please upload a clearer ID").andExpect(status().isOk());
        awaitStatus(applicant, id, "INFORMATION_REQUESTED");

        fireTimer(id, "Timer_InformationDeadline");

        awaitStatus(applicant, id, "EXPIRED");
        assertThat(runtime.createProcessInstanceQuery().processInstanceBusinessKey(id).count()).isZero();
        // An expired application frees the applicant and the seller ID for a new attempt.
        mvc.perform(post("/api/v1/applications").with(applicant).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle, kyc())))
                .andExpect(status().isCreated());
    }

    @Test
    void kycDetailsAreValidatedByTheForm() throws Exception {
        Map<String, Object> invalid = new LinkedHashMap<>(kyc());
        invalid.put("iban", "not an iban");
        invalid.put("country", "US");
        invalid.remove("phone");
        mvc.perform(post("/api/v1/applications").with(applicant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle(), invalid)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_KYC"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("iban")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("phone")));

        // Form.io accepts any select value on the server; the platform validator does not.
        Map<String, Object> wrongCountry = new LinkedHashMap<>(kyc());
        wrongCountry.put("country", "US");
        mvc.perform(post("/api/v1/applications").with(applicant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle(), wrongCountry)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("country"));

        // Fields the form does not define are dropped, not stored.
        Map<String, Object> extra = new LinkedHashMap<>(kyc());
        extra.put("preApproved", true);
        mvc.perform(post("/api/v1/applications").with(applicant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle(), extra)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kyc.preApproved").doesNotExist())
                .andExpect(jsonPath("$.kyc.legalName").value("Nebula Trading LLC"));

        mvc.perform(post("/api/v1/applications").with(applicant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Not Valid!", kyc())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_SELLER_ID"));
    }

    @Test
    void sellerIdsCannotBeTakenOver() throws Exception {
        // "acme" already belongs to a seller in Keycloak.
        mvc.perform(post("/api/v1/applications").with(applicant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("acme", kyc())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELLER_ID_TAKEN"));

        String handle = handle();
        createApplication(applicant(), handle, kyc());
        mvc.perform(post("/api/v1/applications").with(applicant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle, kyc())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELLER_ID_TAKEN"));
    }

    @Test
    void documentsAreVerifiedBeforeSubmission() throws Exception {
        var applicant = applicant();
        String id = createApplication(applicant, handle(), kyc());
        submit(applicant, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENTS_MISSING"));

        // A script uploaded as a PDF is rejected and deleted.
        upload(applicant, id, "OWNER_ID", "application/pdf", "#!/bin/sh\necho owned\n".getBytes(StandardCharsets.US_ASCII))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT"));
        // A real image declared as a PDF is rejected too.
        upload(applicant, id, "OWNER_ID", "application/pdf", PNG).andExpect(status().isUnprocessableContent());
        upload(applicant, id, "OWNER_ID", "image/png", PNG).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
        mvc.perform(post("/api/v1/applications/{id}/documents", id).with(applicant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TRADE_LICENCE", "contentType": "text/html", "sizeBytes": 10}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));
        submit(applicant, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Verified documents are missing: [TRADE_LICENCE, BANK_LETTER]"));

        // Reviewers read documents through short-lived signed links.
        String verified = verifiedDocument(json.readTree(mvc.perform(get("/api/v1/applications/{id}", id).with(applicant))
                .andReturn().getResponse().getContentAsString()));
        String link = json.readTree(mvc.perform(get("/api/v1/applications/{id}/documents/{doc}", id, verified)
                        .with(complianceOfficer("officer-7")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("url").asString();
        var download = http.send(HttpRequest.newBuilder(URI.create(link)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PNG);
    }

    @Test
    void applicationsArePrivateToTheirApplicantAndReviewers() throws Exception {
        var owner = applicant();
        String id = createApplication(owner, handle(), kyc());

        mvc.perform(get("/api/v1/applications/{id}", id).with(applicant()))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/applications/{id}", id).with(applicant()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle(), kyc())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reviews").with(owner)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/applications/{id}", id).with(complianceOfficer("officer-8")))
                .andExpect(status().isOk());
        // Reviewers review; they cannot apply on someone's behalf.
        mvc.perform(post("/api/v1/applications").with(complianceOfficer("officer-8"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(handle(), kyc())))
                .andExpect(status().isForbidden());
    }

    // --- helpers -------------------------------------------------------------------------------

    record KeycloakUser(String id, String username, String password) {
        JwtRequestPostProcessor caller() {
            return jwt().jwt(token -> token.subject(id)).authorities(new SimpleGrantedAuthority("application:submit"));
        }
    }

    static JwtRequestPostProcessor applicant() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("application:submit"));
    }

    static JwtRequestPostProcessor complianceOfficer(String subject) {
        return jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("application:review"));
    }

    static JwtRequestPostProcessor complianceLead(String subject) {
        return jwt().jwt(token -> token.subject(subject))
                .authorities(new SimpleGrantedAuthority("application:review-senior"));
    }

    static String handle() {
        return "shop-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String iban() {
        String digits = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits()) + "0000000000000000000";
        return "AE07" + digits.substring(0, 19);
    }

    static String licence() {
        return "CN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    static Map<String, Object> kyc() {
        return kyc("AE070331234567890123456", "CN-1234567");
    }

    static Map<String, Object> kyc(String iban, String licence) {
        Map<String, Object> kyc = new LinkedHashMap<>();
        kyc.put("legalName", "Nebula Trading LLC");
        kyc.put("businessType", "company");
        kyc.put("tradeLicenceNumber", licence);
        kyc.put("country", "AE");
        kyc.put("expectedMonthlyOrders", 250);
        kyc.put("address", "Office 12, Business Bay, Dubai");
        kyc.put("phone", "+971501234567");
        kyc.put("iban", iban);
        return kyc;
    }

    private String body(String handle, Map<String, Object> kyc) {
        return json.writeValueAsString(Map.of("sellerId", handle, "kyc", kyc));
    }

    private String createApplication(JwtRequestPostProcessor caller, String handle, Map<String, Object> kyc)
            throws Exception {
        // A unique IBAN and licence per application, unless the test shares them on purpose.
        Map<String, Object> details = new LinkedHashMap<>(kyc);
        if (kyc.get("iban").equals(kyc().get("iban"))) {
            details.put("iban", iban());
            details.put("tradeLicenceNumber", licence());
        }
        String response = mvc.perform(post("/api/v1/applications").with(caller).contentType(MediaType.APPLICATION_JSON)
                        .content(body(handle, details)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asString();
    }

    private void uploadAllDocuments(JwtRequestPostProcessor caller, String id) throws Exception {
        upload(caller, id, "TRADE_LICENCE", "application/pdf", PDF).andExpect(status().isOk());
        upload(caller, id, "OWNER_ID", "image/png", PNG).andExpect(status().isOk());
        upload(caller, id, "BANK_LETTER", "application/pdf", PDF).andExpect(status().isOk());
    }

    /** Requests an upload URL, PUTs the bytes straight to MinIO, then asks for verification. */
    private ResultActions upload(JwtRequestPostProcessor caller, String id, String type, String contentType,
            byte[] bytes) throws Exception {
        JsonNode ticket = json.readTree(mvc.perform(post("/api/v1/applications/{id}/documents", id).with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", type, "contentType", contentType,
                                "sizeBytes", bytes.length))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        var request = HttpRequest.newBuilder(URI.create(ticket.path("uploadUrl").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        ticket.path("uploadHeaders").properties().forEach(header -> {
            if (!header.getKey().equalsIgnoreCase("content-length")) {
                request.header(header.getKey(), header.getValue().asString());
            }
        });
        assertThat(http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        return mvc.perform(post("/api/v1/applications/{id}/documents/{doc}/complete", id,
                ticket.path("document").path("id").asString()).with(caller));
    }

    private ResultActions submit(JwtRequestPostProcessor caller, String id) throws Exception {
        return mvc.perform(post("/api/v1/applications/{id}/submit", id).with(caller));
    }

    private ResultActions decide(JwtRequestPostProcessor reviewer, String taskId, String decision, String note)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("decision", decision);
        request.put("note", note);
        return mvc.perform(post("/api/v1/reviews/{task}/decision", taskId).with(reviewer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private List<JsonNode> queue(JwtRequestPostProcessor reviewer) throws Exception {
        JsonNode items = json.readTree(mvc.perform(get("/api/v1/reviews").with(reviewer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<JsonNode> list = new ArrayList<>();
        items.forEach(list::add);
        return list;
    }

    private JsonNode awaitReviewTask(JwtRequestPostProcessor reviewer, String applicationId) {
        return awaitReviewTask(reviewer, applicationId, "REVIEW");
    }

    private JsonNode awaitReviewTask(JwtRequestPostProcessor reviewer, String applicationId, String stage) {
        JsonNode[] found = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            found[0] = queue(reviewer).stream()
                    .filter(item -> item.path("applicationId").asString().equals(applicationId))
                    .filter(item -> item.path("stage").asString().equals(stage))
                    .findFirst().orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    private void awaitStatus(JwtRequestPostProcessor caller, String id, String expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/applications/{id}", id).with(caller))
                        .andExpect(jsonPath("$.status").value(expected)));
    }

    /** Fires a timer now instead of waiting days for it. */
    private void fireTimer(String applicationId, String timerId) {
        String processInstance = runtime.createProcessInstanceQuery().processInstanceBusinessKey(applicationId)
                .singleResult().getId();
        var timer = management.createJobQuery().processInstanceId(processInstance).timers().activityId(timerId)
                .singleResult();
        assertThat(timer).as("timer %s", timerId).isNotNull();
        management.executeJob(timer.getId());
    }

    private String verifiedDocument(JsonNode application) {
        for (JsonNode document : application.path("documents")) {
            if (document.path("status").asString().equals("VERIFIED")) {
                return document.path("id").asString();
            }
        }
        throw new IllegalStateException("no verified document");
    }

    private List<JsonNode> eventsFor(String applicationId, int expected) {
        List<JsonNode> events = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("sellers.applications.v1"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (applicationId.equals(record.key())) {
                        events.add(json.readTree(record.value()));
                    }
                });
                return events.size() >= expected;
            });
        }
        return events;
    }

    // --- Keycloak --------------------------------------------------------------------------------

    /** A real Keycloak user with the buyer role, so provisioning and sign-in can be checked. */
    private KeycloakUser newKeycloakUser() throws Exception {
        String admin = adminToken();
        String username = "applicant-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "applicant-password-dev";
        var created = http.send(HttpRequest.newBuilder(URI.create(keycloakUrl() + "/admin/realms/souqly/users"))
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "username", username, "email", username + "@example.com", "emailVerified", true,
                        "firstName", "Ava", "lastName", "Applicant", "enabled", true,
                        "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false))))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        String id = location.substring(location.lastIndexOf('/') + 1);
        JsonNode buyer = json.readTree(http.send(HttpRequest.newBuilder(
                        URI.create(keycloakUrl() + "/admin/realms/souqly/roles/buyer"))
                .header("Authorization", "Bearer " + admin).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        http.send(HttpRequest.newBuilder(URI.create(keycloakUrl() + "/admin/realms/souqly/users/" + id
                        + "/role-mappings/realm"))
                .header("Authorization", "Bearer " + admin).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("[" + buyer + "]")).build(), HttpResponse.BodyHandlers.discarding());
        return new KeycloakUser(id, username, password);
    }

    private String adminToken() throws Exception {
        return token(keycloakUrl() + "/realms/master/protocol/openid-connect/token",
                "grant_type=password&client_id=admin-cli&username=admin&password=admin");
    }

    private String userToken(String username, String password) throws Exception {
        return token(keycloakUrl() + "/realms/souqly/protocol/openid-connect/token",
                "grant_type=password&client_id=souqly-dev-cli&username=" + username + "&password="
                        + URLEncoder.encode(password, StandardCharsets.UTF_8));
    }

    private String token(String url, String form) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("access_token").asString();
    }

    private JsonNode claims(String token) {
        return json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private String keycloakUrl() {
        return TestcontainersConfiguration.keycloakUrl(keycloak);
    }
}
