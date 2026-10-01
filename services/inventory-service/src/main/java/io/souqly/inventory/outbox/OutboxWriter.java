package io.souqly.inventory.outbox;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Appends events to the outbox. Must be called inside the transaction that changes state. */
@Component
public class OutboxWriter {

    private final MongoTemplate mongo;
    private final JsonMapper json;

    public OutboxWriter(MongoTemplate mongo, JsonMapper json) {
        this.mongo = mongo;
        this.json = json;
    }

    public record Message(String eventId, String aggregateType, String aggregateId, String eventType,
            Instant occurredAt, Object payload) {
    }

    public void append(Message message) {
        appendAll(List.of(message));
    }

    public void appendAll(List<Message> messages) {
        mongo.insert(messages.stream()
                .map(m -> new OutboxEvent(m.eventId(), m.aggregateType(), m.aggregateId(), m.eventType(),
                        json.writeValueAsString(m.payload()), m.occurredAt(), null, null, 0))
                .toList(), OutboxEvent.class);
    }
}
