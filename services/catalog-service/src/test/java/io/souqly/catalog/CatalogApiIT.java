package io.souqly.catalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogApiIT {

    /** A real 1x1 PNG. */
    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    static final JwtRequestPostProcessor ADMIN = jwt().authorities(
            new SimpleGrantedAuthority("category:manage"), new SimpleGrantedAuthority("product:write-any"));
    static final JwtRequestPostProcessor ACME = seller("acme");
    static final JwtRequestPostProcessor GLOBEX = seller("globex");

    final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void categories() throws Exception {
        for (var category : Map.of("phones", "category-phones", "fashion", "category-fashion").entrySet()) {
            mvc.perform(put("/api/v1/categories/{slug}", category.getKey()).with(ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"name": {"en": "%s", "ar": "فئة"}, "formPath": "%s"}
                                    """.formatted(category.getKey(), category.getValue())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void sellerCreatesAListingValidatedByTheCategoryForm() throws Exception {
        createPhone(ACME, newSku(), Map.of("storage", "128", "color", "black", "network", "5g", "screenInches", 6.1,
                "dualSim", true, "isFeatured", true))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", startsWith("/api/v1/products/")))
                .andExpect(jsonPath("$.sellerId").value("acme"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                // Form.io turned "128" into a number; the catalog stores the form's canonical value.
                .andExpect(jsonPath("$.attributes.storage").value("128"))
                // Fields the form does not define are dropped, not stored.
                .andExpect(jsonPath("$.attributes.isFeatured").doesNotExist())
                .andExpect(jsonPath("$.facets[?(@.name == 'storage')].valueLabel").value("128 GB"))
                .andExpect(jsonPath("$.facets[?(@.name == 'network')].value").value("5g"));
    }

    @Test
    void invalidAttributesAreRejectedWithFieldErrors() throws Exception {
        createPhone(ACME, newSku(), Map.of("storage", "128", "color", "black", "screenInches", 20))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_ATTRIBUTES"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("network")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("screenInches")));
    }

    @Test
    void optionsOutsideTheFormAreRejected() throws Exception {
        // Form.io community edition accepts this; the catalog closes the gap.
        createPhone(ACME, newSku(), Map.of("storage", "1024", "color", "black", "network", "5g", "screenInches", 6.1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("storage"));
    }

    @Test
    void unknownCategoriesAndDuplicateSkusAreRejected() throws Exception {
        mvc.perform(post("/api/v1/products").with(ACME).contentType(MediaType.APPLICATION_JSON)
                        .content(listing(newSku(), "garden", Map.of())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CATEGORY"));

        String sku = newSku();
        createPhone(ACME, sku, validPhone()).andExpect(status().isCreated());
        createPhone(GLOBEX, sku, validPhone())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_TAKEN"));
    }

    @Test
    void updatesUseOptimisticConcurrency() throws Exception {
        String id = idOf(createPhone(ACME, newSku(), validPhone()));
        String update = listing(null, "phones", Map.of("storage", "256", "color", "blue", "network", "5g",
                "screenInches", 6.7));

        mvc.perform(put("/api/v1/products/{id}", id).with(ACME).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mvc.perform(put("/api/v1/products/{id}", id).with(ACME).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.attributes.storage").value("256"));

        // A second editor still holding version 1 cannot overwrite the change.
        mvc.perform(put("/api/v1/products/{id}", id).with(ACME).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isPreconditionFailed())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(2));
    }

    @Test
    void sellersOnlyTouchTheirOwnListings() throws Exception {
        String id = idOf(createPhone(ACME, newSku(), validPhone()));

        mvc.perform(put("/api/v1/products/{id}", id).with(GLOBEX).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(listing(null, "phones", validPhone())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_PRODUCT_OWNER"));
        mvc.perform(post("/api/v1/products/{id}/images", id).with(GLOBEX).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\": \"image/png\", \"sizeBytes\": 10}"))
                .andExpect(status().isForbidden());

        createPhone(jwt().authorities(new SimpleGrantedAuthority("product:write")), newSku(), validPhone())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_IDENTITY_REQUIRED"));
    }

    @Test
    void draftsAreInvisibleToEveryoneButTheirOwner() throws Exception {
        String id = idOf(createPhone(ACME, newSku(), validPhone()));

        mvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/{id}", id).with(GLOBEX)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/products/{id}", id).with(ACME)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content(listing(newSku(), "phones", validPhone())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void imageUploadedStraightToStorageIsVerifiedThenPublished() throws Exception {
        String id = idOf(createPhone(ACME, newSku(), validPhone()));
        mvc.perform(post("/api/v1/products/{id}/publish", id).with(ACME))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMAGE_REQUIRED"));

        JsonNode ticket = requestUpload(id, "image/png", PNG.length);
        assertThat(upload(ticket, PNG)).isEqualTo(200);

        String body = mvc.perform(post("/api/v1/products/{id}/images/{image}/complete", id,
                        ticket.get("imageId").asString()).with(ACME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images[0].status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        String url = json.readTree(body).get("images").get(0).get("url").asString();

        // Published images are public, served with the verified type.
        var image = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).isEqualTo(PNG);
        assertThat(image.headers().firstValue("Content-Type")).contains("image/png");

        mvc.perform(post("/api/v1/products/{id}/publish", id).with(ACME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isOk());
    }

    @Test
    void fileThatIsNotTheDeclaredImageIsRejectedAndDeleted() throws Exception {
        String id = idOf(createPhone(ACME, newSku(), validPhone()));
        byte[] script = "<script>alert('x')</script>".getBytes(StandardCharsets.UTF_8);

        JsonNode ticket = requestUpload(id, "image/png", script.length);
        assertThat(upload(ticket, script)).isEqualTo(200);

        mvc.perform(post("/api/v1/products/{id}/images/{image}/complete", id, ticket.get("imageId").asString())
                        .with(ACME))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        mvc.perform(get("/api/v1/products/{id}", id).with(ACME))
                .andExpect(jsonPath("$.images[0].status").value("REJECTED"));
    }

    @Test
    void uploadUrlOnlyAcceptsTheDeclaredSize() throws Exception {
        String id = idOf(createPhone(ACME, newSku(), validPhone()));
        JsonNode ticket = requestUpload(id, "image/png", PNG.length + 100);

        // Content length is part of the signature, so the store itself refuses a different size.
        assertThat(upload(ticket, PNG)).isEqualTo(403);
        mvc.perform(post("/api/v1/products/{id}/images/{image}/complete", id, ticket.get("imageId").asString())
                        .with(ACME))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_MISSING"));
    }

    @Test
    void onlySupportedImageTypesAndSizes() throws Exception {
        String id = idOf(createPhone(ACME, newSku(), validPhone()));
        mvc.perform(post("/api/v1/products/{id}/images", id).with(ACME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\": \"image/gif\", \"sizeBytes\": 100}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
        mvc.perform(post("/api/v1/products/{id}/images", id).with(ACME).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\": \"image/png\", \"sizeBytes\": 50000000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @Test
    void categoryFormsArePublicForTheSellerPortal() throws Exception {
        mvc.perform(get("/api/v1/categories/phones/form"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("category-phones"))
                .andExpect(jsonPath("$.components[*].key", hasItem("storage")));
        mvc.perform(get("/api/v1/categories")).andExpect(jsonPath("$[*].slug", hasItem("fashion")));
    }

    private ResultActions createPhone(JwtRequestPostProcessor caller, String sku, Map<String, Object> attributes)
            throws Exception {
        return mvc.perform(post("/api/v1/products").with(caller).contentType(MediaType.APPLICATION_JSON)
                .content(listing(sku, "phones", attributes)));
    }

    private String listing(String sku, String category, Map<String, Object> attributes) {
        var body = new java.util.LinkedHashMap<String, Object>();
        if (sku != null) {
            body.put("sku", sku);
        }
        body.put("category", category);
        body.put("title", Map.of("en", "Acme Phone X", "ar", "هاتف أكمي إكس"));
        body.put("description", Map.of("en", "A fast phone with a great camera", "ar", "هاتف سريع بكاميرا رائعة"));
        body.put("brand", "Acme");
        body.put("price", Map.of("amount", "2499.00", "currency", "AED"));
        body.put("attributes", attributes);
        return json.writeValueAsString(body);
    }

    private static Map<String, Object> validPhone() {
        return Map.of("storage", "128", "color", "black", "network", "5g", "screenInches", 6.1);
    }

    private JsonNode requestUpload(String productId, String contentType, long size) throws Exception {
        String body = mvc.perform(post("/api/v1/products/{id}/images", productId).with(ACME)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\": \"%s\", \"sizeBytes\": %d}".formatted(contentType, size)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Uploads like a browser would: straight to storage, with the headers the ticket lists. */
    private int upload(JsonNode ticket, byte[] bytes) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(ticket.get("uploadUrl").asString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        ticket.get("headers").properties().forEach(header -> {
            if (!header.getKey().equalsIgnoreCase("content-length")) {
                request.header(header.getKey(), header.getValue().asString());
            }
        });
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String idOf(ResultActions created) throws Exception {
        return created.andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location")
                .replaceAll(".*/", "");
    }

    private static String newSku() {
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static JwtRequestPostProcessor seller(String sellerId) {
        return jwt().jwt(token -> token.claim("seller_id", sellerId))
                .authorities(new SimpleGrantedAuthority("product:write"));
    }
}
