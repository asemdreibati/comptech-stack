package io.souqly.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Polls the outbox and publishes pending events to their topics, keyed by aggregate ID so all
 * events for one aggregate land on the same partition in order. Delivery is at least once: a
 * crash between the Kafka ack and marking the event published causes a resend, so consumers
 * deduplicate on the {@code eventId} header.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final MongoTemplate mongo;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties config;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(MongoTemplate mongo, KafkaTemplate<String, String> kafka, OutboxProperties config,
            Clock clock, MeterRegistry meterRegistry) {
        this.mongo = mongo;
        this.kafka = kafka;
        this.config = config;
        this.clock = clock;
        this.published = Counter.builder("souqly.outbox.published").register(meterRegistry);
        this.failures = Counter.builder("souqly.outbox.failures").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${souqly.outbox.poll-interval:PT0.5S}")
    public void publishPending() {
        for (int i = 0; i < config.batchSize(); i++) {
            OutboxEvent event = claimNext();
            if (event == null) {
                return;
            }
            if (!publish(event)) {
                // Stop the batch so later events for the same aggregate are not sent first.
                return;
            }
        }
    }

    private OutboxEvent claimNext() {
        var now = clock.instant();
        var pending = query(where("publishedAt").is(null)
                .orOperator(where("lockedUntil").is(null), where("lockedUntil").lt(now)))
                .with(Sort.by("occurredAt"));
        var lease = new Update().set("lockedUntil", now.plus(config.lease())).inc("attempts", 1);
        return mongo.findAndModify(pending, lease, FindAndModifyOptions.options().returnNew(true), OutboxEvent.class);
    }

    private boolean publish(OutboxEvent event) {
        var record = new ProducerRecord<String, String>(event.topic(), event.aggregateId(), event.payload());
        record.headers()
                .add("eventId", event.id().getBytes(StandardCharsets.UTF_8))
                .add("eventType", event.eventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(config.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        catch (ExecutionException | TimeoutException ex) {
            failures.increment();
            log.warn("Failed to publish outbox event {} (attempt {}): {}", event.id(), event.attempts(),
                    ex.getMessage());
            return false;
        }
        mongo.updateFirst(query(Criteria.where("_id").is(event.id())),
                new Update().set("publishedAt", clock.instant()).unset("lockedUntil"), OutboxEvent.class);
        published.increment();
        return true;
    }
}
