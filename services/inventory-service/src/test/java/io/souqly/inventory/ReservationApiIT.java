package io.souqly.inventory;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationApiIT {

    @Autowired
    MockMvc mvc;

    @Test
    void reserveConfirmMovesUnitsOutOfStock() throws Exception {
        String sku = newSku();
        restock(sku, 10);

        String id = reserve("order-" + UUID.randomUUID(), sku, 3)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/reservations/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");

        stock(sku).andExpect(jsonPath("$.available").value(7)).andExpect(jsonPath("$.reserved").value(3));

        mvc.perform(post("/api/v1/reservations/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        stock(sku).andExpect(jsonPath("$.available").value(7)).andExpect(jsonPath("$.reserved").value(0));

        // Confirming twice is harmless; releasing a confirmed reservation is not allowed.
        mvc.perform(post("/api/v1/reservations/{id}/confirm", id)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/reservations/{id}/release", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));
    }

    @Test
    void releaseReturnsUnitsToStock() throws Exception {
        String sku = newSku();
        restock(sku, 5);
        String id = idOf(reserve("order-" + UUID.randomUUID(), sku, 5).andExpect(status().isCreated()));
        stock(sku).andExpect(jsonPath("$.available").value(0));

        mvc.perform(post("/api/v1/reservations/{id}/release", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(post("/api/v1/reservations/{id}/release", id)).andExpect(status().isOk());

        stock(sku).andExpect(jsonPath("$.available").value(5)).andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    void retriedRequestReturnsTheOriginalReservation() throws Exception {
        String sku = newSku();
        String orderId = "order-" + UUID.randomUUID();
        restock(sku, 10);

        String id = idOf(reserve(orderId, sku, 2).andExpect(status().isCreated()));
        reserve(orderId, sku, 2)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        stock(sku).andExpect(jsonPath("$.available").value(8));

        reserve(orderId, sku, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void multiLineReservationIsAllOrNothing() throws Exception {
        String plenty = newSku();
        String scarce = newSku();
        restock(plenty, 10);
        restock(scarce, 1);

        mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId": "%s", "lines": [{"sku": "%s", "quantity": 4}, {"sku": "%s", "quantity": 2}]}
                """.formatted("order-" + UUID.randomUUID(), plenty, scarce)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.sku").value(scarce))
                .andExpect(jsonPath("$.available").value(1));

        stock(plenty).andExpect(jsonPath("$.available").value(10)).andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    void flashSaleGateRejectsOnceTokensRunOut() throws Exception {
        String sku = newSku();
        restock(sku, 10);
        mvc.perform(put("/api/v1/flash-sales/{sku}", sku).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingTokens").value(2));

        reserve("order-" + UUID.randomUUID(), sku, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.available").value(2));
        reserve("order-" + UUID.randomUUID(), sku, 2).andExpect(status().isCreated());
        reserve("order-" + UUID.randomUUID(), sku, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        // Stock outside the sale allocation is untouched.
        stock(sku).andExpect(jsonPath("$.available").value(8));
        mvc.perform(get("/api/v1/flash-sales/{sku}", sku)).andExpect(jsonPath("$.remainingTokens").value(0));
    }

    @Test
    void retryingAReleasedOrderDoesNotLeakGateTokens() throws Exception {
        String sku = newSku();
        String orderId = "order-" + UUID.randomUUID();
        restock(sku, 5);
        mvc.perform(put("/api/v1/flash-sales/{sku}", sku)).andExpect(jsonPath("$.remainingTokens").value(5));

        String id = idOf(reserve(orderId, sku, 2).andExpect(status().isCreated()));
        mvc.perform(get("/api/v1/flash-sales/{sku}", sku)).andExpect(jsonPath("$.remainingTokens").value(3));
        mvc.perform(post("/api/v1/reservations/{id}/release", id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/flash-sales/{sku}", sku)).andExpect(jsonPath("$.remainingTokens").value(5));

        reserve(orderId, sku, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/api/v1/flash-sales/{sku}", sku)).andExpect(jsonPath("$.remainingTokens").value(5));
        stock(sku).andExpect(jsonPath("$.available").value(5));
    }

    @Test
    void cannotAllocateMoreTokensThanAvailable() throws Exception {
        String sku = newSku();
        restock(sku, 3);
        mvc.perform(put("/api/v1/flash-sales/{sku}", sku).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\": 4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId": "bad id!", "lines": [{"sku": "A", "quantity": 0}]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasItem(containsString("orderId"))))
                .andExpect(jsonPath("$.errors", hasItem(containsString("lines[0].quantity"))));

        mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId": "o-1", "lines": [{"sku": "A", "quantity": 1}, {"sku": "A", "quantity": 1}]}
                """))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/stock/{sku}", "does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKU_NOT_FOUND"));
        mvc.perform(get("/api/v1/reservations/{id}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void exposesOperationalEndpoints() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/reservations']").exists());
    }

    private static String newSku() {
        return "SKU-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void restock(String sku, int quantity) throws Exception {
        mvc.perform(post("/api/v1/stock/{sku}/restock", sku).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": " + quantity + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions stock(String sku) throws Exception {
        return mvc.perform(get("/api/v1/stock/{sku}", sku)).andExpect(status().isOk());
    }

    private ResultActions reserve(String orderId, String sku, int quantity) throws Exception {
        return mvc.perform(post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId": "%s", "lines": [{"sku": "%s", "quantity": %d}]}
                """.formatted(orderId, sku, quantity)));
    }

    private static String idOf(ResultActions result) {
        return result.andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");
    }
}
