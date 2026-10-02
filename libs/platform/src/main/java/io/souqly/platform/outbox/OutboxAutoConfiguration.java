package io.souqly.platform.outbox;

import java.time.Clock;
import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Transactional outbox for services that keep their state in MongoDB and publish to Kafka. */
@AutoConfiguration(after = {DataMongoAutoConfiguration.class, KafkaAutoConfiguration.class})
@ConditionalOnClass({MongoTemplate.class, KafkaTemplate.class})
@ConditionalOnBean(MongoTemplate.class)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    OutboxWriter outboxWriter(MongoTemplate mongo, JsonMapper json) {
        return new OutboxWriter(mongo, json);
    }

    @Bean
    @ConditionalOnProperty(name = "souqly.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelay outboxRelay(MongoTemplate mongo, KafkaTemplate<String, String> kafka, OutboxProperties properties,
            Clock clock, MeterRegistry meterRegistry) {
        return new OutboxRelay(mongo, kafka, properties, clock, meterRegistry);
    }

    /** The outbox must exist before the first transaction writes to it. */
    @Bean
    SmartInitializingSingleton outboxSchemaInitializer(MongoTemplate mongo) {
        return () -> {
            if (!mongo.collectionExists(OutboxEvent.class)) {
                mongo.createCollection(OutboxEvent.class);
            }
            var outbox = mongo.indexOps(OutboxEvent.class);
            outbox.createIndex(new Index()
                    .on("publishedAt", Direction.ASC).on("occurredAt", Direction.ASC).named("ix_pending"));
            outbox.createIndex(new Index()
                    .on("publishedAt", Direction.ASC).expire(Duration.ofDays(7)).named("ttl_published"));
        };
    }
}
