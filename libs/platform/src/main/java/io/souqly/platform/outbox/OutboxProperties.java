package io.souqly.platform.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code pollInterval} is read by the relay's {@code @Scheduled} placeholder. */
@ConfigurationProperties("souqly.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("PT0.5S") Duration pollInterval,
        @DefaultValue("200") int batchSize,
        @DefaultValue("PT30S") Duration lease,
        @DefaultValue("PT10S") Duration sendTimeout) {
}
