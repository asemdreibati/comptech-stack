package io.souqly.order.payment;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the payment service provider (a Stripe-style API).
 *
 * <p>The PSP deduplicates by {@code Idempotency-Key}: a repeated request with the same key returns
 * the original result instead of charging again. Every charge uses a key derived from the order,
 * so after a timeout (when the charge may or may not have happened) the saga simply asks again
 * with the same key and gets the definitive answer. A timeout is never treated as a decline.
 */
@Component
public class PaymentGateway {

    public sealed interface Outcome {
    }

    public record Succeeded(String paymentId) implements Outcome {
    }

    public record Declined(String code, String message) implements Outcome {
    }

    /** The request may or may not have taken effect; repeat it with the same key. */
    public record Unknown(String reason) implements Outcome {
    }

    private final RestClient http;
    private final JsonMapper json;

    public PaymentGateway(@Qualifier("paymentsHttp") RestClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    public Outcome charge(String idempotencyKey, String orderId, BigDecimal amount, String currency,
            String paymentMethod) {
        var body = Map.of(
                "amount", minorUnits(amount, currency),
                "currency", currency.toLowerCase(Locale.ROOT),
                "payment_method", paymentMethod,
                "confirm", true,
                "metadata", Map.of("order_id", orderId));
        return post("/v1/payment_intents", idempotencyKey, body);
    }

    /** PSPs take integer minor units: 12.50 AED is 1250, 1.250 KWD is 1250. */
    public static long minorUnits(BigDecimal amount, String currency) {
        return amount.movePointRight(Currency.getInstance(currency).getDefaultFractionDigits()).longValueExact();
    }

    public Outcome refund(String idempotencyKey, String paymentId) {
        return post("/v1/refunds", idempotencyKey, Map.of("payment_intent", paymentId));
    }

    private Outcome post(String path, String idempotencyKey, Map<String, ?> body) {
        try {
            return http.post().uri(path)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        JsonNode answer = json.readTree(res.getBody().readAllBytes());
                        if (status == 200 && "succeeded".equals(answer.path("status").asString())) {
                            return new Succeeded(answer.path("id").asString());
                        }
                        if (status == 402 || (status >= 400 && status < 500 && status != 409 && status != 429)) {
                            JsonNode error = answer.path("error");
                            return new Declined(error.path("code").asString("payment_failed"),
                                    error.path("message").asString("Payment failed"));
                        }
                        // 409 (concurrent request with this key), 429 and 5xx: ask again later.
                        return new Unknown("PSP answered " + status);
                    }, true);
        }
        catch (RestClientException ex) {
            return new Unknown("PSP unreachable or timed out: " + ex.getMessage());
        }
    }
}
