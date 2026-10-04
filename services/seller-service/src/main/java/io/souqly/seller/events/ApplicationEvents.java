package io.souqly.seller.events;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.souqly.seller.application.SellerApplication;
import io.souqly.seller.config.OnboardingProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes application events from inside workflow jobs.
 *
 * <p>Every status change runs as an asynchronous job, and the job's transaction is only committed
 * after Kafka acknowledged the event. If publishing fails, the job fails, the status change rolls
 * back and the engine retries the job. The engine's job table therefore does what a transactional
 * outbox would: no status change without its event. As with an outbox, delivery is at least once,
 * so consumers apply events by {@code version}.
 */
@Component
public class ApplicationEvents {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final IdentifierHasher hasher;
    private final String topic;
    private final Clock clock;

    public ApplicationEvents(KafkaTemplate<String, String> kafka, JsonMapper json, IdentifierHasher hasher,
            OnboardingProperties properties, Clock clock) {
        this.kafka = kafka;
        this.json = json;
        this.hasher = hasher;
        this.topic = properties.topics().applications();
        this.clock = clock;
    }

    public void publish(SellerApplication application, String eventType) {
        var event = new SellerApplicationEvent(UUID.randomUUID().toString(), eventType, clock.instant(),
                application.id(), application.applicantId(), application.sellerHandle(), application.status(),
                application.riskTier(), application.kycText("country"), application.kycText("businessType"),
                new SellerApplicationEvent.Identifiers(hasher.hash(application.kycText("iban")),
                        hasher.hash(application.kycText("phone")),
                        hasher.hash(application.kycText("tradeLicenceNumber"))),
                application.version());
        var record = new ProducerRecord<String, String>(topic, application.id().toString(),
                json.writeValueAsString(event));
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
            throw new IllegalStateException("Could not publish " + eventType + " for " + application.id(), ex);
        }
    }
}
