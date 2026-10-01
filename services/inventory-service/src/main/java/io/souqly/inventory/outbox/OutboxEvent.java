package io.souqly.inventory.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An event written in the same transaction as the state change it describes, then relayed
 * to Kafka. {@code lockedUntil} is a lease so several relay instances never send the same
 * event concurrently; published events are removed by a TTL index after seven days.
 */
@Document("outbox")
public record OutboxEvent(
        @Id String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        Instant occurredAt,
        Instant publishedAt,
        Instant lockedUntil,
        int attempts) {
}
