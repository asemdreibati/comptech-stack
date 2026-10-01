package io.souqly.inventory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end against the real Souqly realm ({@code infra/keycloak/souqly-realm.json}): real
 * signed tokens, real issuer, audience and role mappings. Proves the realm and the service agree.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class KeycloakIT {

    @Container
    static final GenericContainer<?> keycloak = new GenericContainer<>(DockerImageName.parse("keycloak/keycloak:26.4"))
            .withCopyFileToContainer(MountableFile.forHostPath("../../infra/keycloak/souqly-realm.json"),
                    "/opt/keycloak/data/import/souqly-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/souqly/.well-known/openid-configuration").forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realmUrl());
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> realmUrl() + "/protocol/openid-connect/certs");
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void serviceAccountTokenReservesStock() throws Exception {
        String ops = clientToken("souqly-ops", "souqly-ops-dev-secret");
        String orderService = clientToken("order-service", "order-service-dev-secret");
        String sku = "KC-" + UUID.randomUUID().toString().substring(0, 8);

        restock(sku, 3, ops).andExpect(status().isOk());
        mvc.perform(post("/api/v1/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + orderService)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderId": "o-%s", "lines": [{"sku": "%s", "quantity": 2}]}
                                """.formatted(UUID.randomUUID(), sku)))
                .andExpect(status().isCreated());

        // The order service holds no stock-writing permission.
        restock(sku, 3, orderService).andExpect(status().isForbidden());
    }

    @Test
    void sellerUserTokenCarriesSellerIdentity() throws Exception {
        String acme = userToken("seller-acme", "seller-password-dev");
        String globex = userToken("seller-globex", "seller-password-dev");
        String sku = "KC-" + UUID.randomUUID().toString().substring(0, 8);

        restock(sku, 4, acme).andExpect(status().isOk()).andExpect(jsonPath("$.sellerId").value("acme"));
        restock(sku, 4, globex).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_SKU_OWNER"));
    }

    @Test
    void tokenNotAddressedToThisServiceIsRejected() throws Exception {
        // Buyers get no inventory roles, so Keycloak does not add this service to the audience.
        String buyer = userToken("buyer", "buyer-password-dev");
        assertThat(claims(buyer)).doesNotContainKey("aud");

        restock("ANY", 1, buyer).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String acme = userToken("seller-acme", "seller-password-dev");
        String[] parts = acme.split("\\.");
        // Swap in a payload claiming to be another seller, keeping the original signature.
        String forged = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"seller_id\":\"acme\"", "\"seller_id\":\"globex\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        restock("ANY", 1, tampered).andExpect(status().isUnauthorized());
    }

    private ResultActions restock(String sku, int quantity, String token) throws Exception {
        return mvc.perform(post("/api/v1/stock/{sku}/restock", sku)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": " + quantity + "}"));
    }

    private String clientToken(String clientId, String secret) throws Exception {
        return token(Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret));
    }

    private String userToken(String username, String password) throws Exception {
        return token(Map.of("grant_type", "password", "client_id", "souqly-dev-cli",
                "username", username, "password", password));
    }

    private String token(Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var response = http.send(HttpRequest.newBuilder(URI.create(realmUrl() + "/protocol/openid-connect/token"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("access_token").asString();
    }

    private Map<String, Object> claims(String token) {
        byte[] payload = Base64.getUrlDecoder().decode(token.split("\\.")[1]);
        return json.readValue(payload, Map.class);
    }

    private static String realmUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/souqly";
    }
}
