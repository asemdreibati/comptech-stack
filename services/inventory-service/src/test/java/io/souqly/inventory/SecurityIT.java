package io.souqly.inventory;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call what. Permissions arrive as authorities exactly as the Keycloak converter would
 * produce them; {@code KeycloakIT} covers real token validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIT {

    private static final JwtRequestPostProcessor BUYER = jwt();
    private static final JwtRequestPostProcessor ORDER_SERVICE =
            jwt().authorities(TestTokens.permissions("stock:read", "reservation:write"));
    private static final JwtRequestPostProcessor SELLER_ACME = seller("acme");
    private static final JwtRequestPostProcessor SELLER_GLOBEX = seller("globex");
    private static final JwtRequestPostProcessor ADMIN = jwt().authorities(TestTokens.permissions(
            "stock:read", "stock:write", "stock:write-any", "flash-sale:manage", "reservation:write"));

    @Autowired
    MockMvc mvc;

    @ParameterizedTest(name = "{0} {1} without a token is 401")
    @CsvSource({
            "GET, /api/v1/stock/ANY",
            "POST, /api/v1/stock/ANY/restock",
            "PUT, /api/v1/flash-sales/ANY",
            "POST, /api/v1/reservations",
            "POST, /api/v1/reservations/ANY/confirm"
    })
    void anonymousCallersAreRejected(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest(name = "{0} {1} as a buyer is 403")
    @CsvSource({
            "GET, /api/v1/stock/ANY",
            "POST, /api/v1/stock/ANY/restock",
            "PUT, /api/v1/flash-sales/ANY",
            "DELETE, /api/v1/flash-sales/ANY",
            "POST, /api/v1/reservations",
            "POST, /api/v1/reservations/ANY/release"
    })
    void buyersCannotUseBackOfficeApis(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).with(BUYER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void orderServiceCanReserveButNotChangeStockOrSales() throws Exception {
        String sku = newSku();
        restock(sku, 5, ADMIN).andExpect(status().isOk());

        mvc.perform(post("/api/v1/reservations").with(ORDER_SERVICE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": "o-%s", "lines": [{"sku": "%s", "quantity": 1}]}
                                """.formatted(UUID.randomUUID(), sku)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/stock/{sku}", sku).with(ORDER_SERVICE)).andExpect(status().isOk());

        restock(sku, 5, ORDER_SERVICE).andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.PUT, "/api/v1/flash-sales/{sku}", sku).with(ORDER_SERVICE))
                .andExpect(status().isForbidden());
    }

    @Test
    void sellersOwnTheSkusTheyCreate() throws Exception {
        String sku = newSku();

        restock(sku, 10, SELLER_ACME)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value("acme"))
                .andExpect(jsonPath("$.available").value(10));
        restock(sku, 5, SELLER_ACME).andExpect(jsonPath("$.available").value(15));

        // Another seller cannot touch it, even though the permission is the same.
        restock(sku, 1_000, SELLER_GLOBEX)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SKU_OWNER"));

        // Operations staff can, and ownership does not change hands.
        restock(sku, 1, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value("acme"))
                .andExpect(jsonPath("$.available").value(16));
    }

    @Test
    void sellersCannotRestockMarketplaceOwnedStock() throws Exception {
        String sku = newSku();
        restock(sku, 10, ADMIN).andExpect(jsonPath("$.sellerId").value(nullValue()));

        restock(sku, 1, SELLER_ACME)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_SKU_OWNER"));
    }

    @Test
    void sellerPermissionWithoutSellerIdentityFailsClosed() throws Exception {
        restock(newSku(), 1, jwt().authorities(TestTokens.permissions("stock:write")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELLER_IDENTITY_REQUIRED"));
    }

    @Test
    void onlyOperationsManageFlashSales() throws Exception {
        String sku = newSku();
        restock(sku, 10, SELLER_ACME).andExpect(status().isOk());

        mvc.perform(request(HttpMethod.PUT, "/api/v1/flash-sales/{sku}", sku).with(SELLER_ACME))
                .andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.PUT, "/api/v1/flash-sales/{sku}", sku).with(ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void healthAndMetricsStayReachableForTheCluster() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    private static JwtRequestPostProcessor seller(String sellerId) {
        return jwt().jwt(token -> token.claim("seller_id", sellerId))
                .authorities(TestTokens.permissions("stock:read", "stock:write"));
    }

    private ResultActions restock(String sku, int quantity, JwtRequestPostProcessor token) throws Exception {
        return mvc.perform(post("/api/v1/stock/{sku}/restock", sku).with(token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\": " + quantity + "}"));
    }

    private static String newSku() {
        return "SEC-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
