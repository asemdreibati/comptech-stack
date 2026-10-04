package io.souqly.returns.events;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.souqly.returns.config.ReturnsProperties;
import io.souqly.returns.returns.ReturnRequest;
import org.apache.kafka.clients.producer.ProducerRecord;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes return events from inside workflow jobs. A job commits only after Kafka acknowledged
 * its event, so a status change never goes unannounced: the engine's job table works as the
 * outbox (see ADR 0005). Delivery is at least once; consumers apply events by {@code version}.
 */
@Component
public class ReturnEvents {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String topic;
    private final Clock clock;

    public ReturnEvents(KafkaTemplate<String, String> kafka, JsonMapper json, ReturnsProperties properties,
            Clock clock) {
        this.kafka = kafka;
        this.json = json;
        this.topic = properties.topics().returns();
        this.clock = clock;
    }

    public void publish(ReturnRequest request, String eventType) {
        var event = new ReturnEvent(UUID.randomUUID().toString(), eventType, clock.instant(), request.id(),
                request.orderId(), request.buyerId(), request.sellerId(), request.status(), request.reason(),
                request.refundAmount(), request.currency(), request.refundId(), request.version());
        var record = new ProducerRecord<String, String>(topic, request.id().toString(), json.writeValueAsString(event));
        record.headers()
                .add("eventId", event.eventId().getBytes(StandardCharsets.UTF_8))
                .add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + eventType, ex);
        }
        catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Could not publish " + eventType + " for " + request.id(), ex);
        }
    }
}
