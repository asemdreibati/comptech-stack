package io.souqly.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for the inventory service. Scheduling intervals are read directly by
 * {@code @Scheduled} placeholders and are listed here for documentation only.
 */
@ConfigurationProperties("souqly.inventory")
public record InventoryProperties(
        @DefaultValue("PT10M") Duration reservationTtl,
        @DefaultValue("PT5S") Duration expirySweepInterval,
        @DefaultValue("500") int expirySweepBatchSize,
        @DefaultValue Topics topics,
        @DefaultValue FlashSale flashSale) {

    /** Kafka topics this service publishes to, through the platform outbox. */
    public record Topics(
            @DefaultValue("inventory.reservation-events.v1") String reservationEvents,
            @DefaultValue("inventory.stock-levels.v1") String stockLevels,
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicas) {
    }

    public record FlashSale(
            /* How long a per-order gate claim is remembered; must outlive the reservation TTL. */
            @DefaultValue("PT24H") Duration claimTtl) {
    }
}
