package io.souqly.order.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("souqly.order")
public record OrderProperties(
        Inventory inventory,
        Payments payments,
        @DefaultValue Saga saga,
        @DefaultValue Topics topics) {

    /** The inventory service, called with this service's own client-credentials token. */
    public record Inventory(URI baseUrl, @DefaultValue("PT2S") Duration timeout) {
    }

    /** The payment service provider (PSP). Calls are idempotent by key, so retries never charge twice. */
    public record Payments(URI baseUrl, String apiKey, @DefaultValue("PT3S") Duration timeout) {
    }

    /**
     * @param lease        how long one instance owns an order while advancing it; a crashed owner's
     *                     orders are picked up once their lease runs out
     * @param maxAttempts  retries of one step before the order is flagged for manual attention
     */
    public record Saga(
            @DefaultValue("PT30S") Duration lease,
            @DefaultValue("PT1S") Duration retryBackoff,
            @DefaultValue("PT1M") Duration maxRetryBackoff,
            @DefaultValue("30") int maxAttempts,
            @DefaultValue("PT2S") Duration recoveryInterval) {
    }

    public record Topics(
            @DefaultValue("orders.order-events.v1") String orders,
            @DefaultValue("catalog.products.v1") String catalogProducts,
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicas) {
    }
}
