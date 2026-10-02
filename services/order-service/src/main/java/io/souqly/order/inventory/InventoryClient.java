package io.souqly.order.inventory;

import java.util.List;
import java.util.Map;

import io.souqly.order.order.OrderLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the inventory service. Every call is idempotent on the inventory side (reservations are
 * keyed by order ID; confirm and release are no-ops when repeated), so the saga can always retry.
 */
@Component
public class InventoryClient {

    /** What a call produced: done, refused for a business reason, or unknown (retry later). */
    public sealed interface Outcome {
    }

    public record Done(String reservationId) implements Outcome {
    }

    public record Refused(String code, String detail) implements Outcome {
    }

    public record Unavailable(String reason) implements Outcome {
    }

    private final RestClient http;
    private final JsonMapper json;

    public InventoryClient(@Qualifier("inventoryHttp") RestClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    /** Reserves every line or none; a retry for the same order returns the original reservation. */
    public Outcome reserve(String orderId, List<OrderLine> lines) {
        var body = Map.of("orderId", orderId, "lines",
                lines.stream().map(l -> Map.of("sku", l.sku(), "quantity", l.quantity())).toList());
        return call(() -> http.post().uri("/reservations").contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)));
    }

    public Outcome confirm(String reservationId) {
        return call(() -> http.post().uri("/reservations/{id}/confirm", reservationId));
    }

    public Outcome release(String reservationId) {
        return call(() -> http.post().uri("/reservations/{id}/release", reservationId));
    }

    private Outcome call(java.util.function.Supplier<RestClient.RequestHeadersSpec<?>> request) {
        try {
            return request.get().exchange((req, res) -> {
                int status = res.getStatusCode().value();
                JsonNode body = json.readTree(res.getBody().readAllBytes());
                if (status == 200 || status == 201) {
                    return new Done(body.path("id").asString());
                }
                if (status == 404 || status == 409 || status == 422) {
                    return new Refused(body.path("code").asString(status == 404 ? "NOT_FOUND" : "CONFLICT"),
                            body.path("detail").asString(""));
                }
                // 401/403 mean misconfigured credentials, 5xx a sick service: neither is a decision.
                return new Unavailable("inventory answered " + status);
            }, true);
        }
        catch (RestClientException ex) {
            return new Unavailable("inventory unreachable: " + ex.getMessage());
        }
    }
}
