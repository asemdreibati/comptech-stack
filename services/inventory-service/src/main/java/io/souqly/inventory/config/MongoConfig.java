package io.souqly.inventory.config;

import java.time.Duration;

import io.souqly.inventory.outbox.OutboxEvent;
import io.souqly.inventory.reservation.Reservation;
import io.souqly.inventory.stock.StockItem;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration(proxyBeanMethods = false)
class MongoConfig {

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    /**
     * Collections must exist before the first transaction touches them, and the unique
     * order index is what makes reservations idempotent, so neither is left to chance.
     */
    @Bean
    SmartInitializingSingleton mongoSchemaInitializer(MongoTemplate mongo) {
        return () -> {
            for (Class<?> type : new Class<?>[] {StockItem.class, Reservation.class, OutboxEvent.class}) {
                if (!mongo.collectionExists(type)) {
                    mongo.createCollection(type);
                }
            }

            var reservations = mongo.indexOps(Reservation.class);
            reservations.createIndex(new Index().on("orderId", Direction.ASC).unique().named("ux_order_id"));
            reservations.createIndex(new Index()
                    .on("status", Direction.ASC).on("expiresAt", Direction.ASC).named("ix_status_expires_at"));

            var outbox = mongo.indexOps(OutboxEvent.class);
            outbox.createIndex(new Index()
                    .on("publishedAt", Direction.ASC).on("occurredAt", Direction.ASC).named("ix_pending"));
            outbox.createIndex(new Index()
                    .on("publishedAt", Direction.ASC).expire(Duration.ofDays(7)).named("ttl_published"));
        };
    }
}
