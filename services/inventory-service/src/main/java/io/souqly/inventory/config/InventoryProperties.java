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
        @DefaultValue Outbox outbox,
        @DefaultValue FlashSale flashSale) {

    public record Outbox(
            @DefaultValue("inventory.reservation-events.v1") String topic,
            @DefaultValue("6") int topicPartitions,
            @DefaultValue("1") short topicReplicas,
            @DefaultValue("PT0.5S") Duration pollInterval,
            @DefaultValue("200") int batchSize,
            @DefaultValue("PT30S") Duration lease,
            @DefaultValue("PT10S") Duration sendTimeout) {
    }

    public record FlashSale(
            /* How long a per-order gate claim is remembered; must outlive the reservation TTL. */
            @DefaultValue("PT24H") Duration claimTtl) {
    }
}
