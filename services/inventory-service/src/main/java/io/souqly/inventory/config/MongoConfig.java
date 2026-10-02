package io.souqly.inventory.config;

import io.souqly.inventory.reservation.Reservation;
import io.souqly.inventory.stock.StockItem;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration(proxyBeanMethods = false)
class MongoConfig {

    /**
     * Collections must exist before the first transaction touches them, and the unique
     * order index is what makes reservations idempotent, so neither is left to chance.
     */
    @Bean
    SmartInitializingSingleton mongoSchemaInitializer(MongoTemplate mongo) {
        return () -> {
            for (Class<?> type : new Class<?>[] {StockItem.class, Reservation.class}) {
                if (!mongo.collectionExists(type)) {
                    mongo.createCollection(type);
                }
            }

            var reservations = mongo.indexOps(Reservation.class);
            reservations.createIndex(new Index().on("orderId", Direction.ASC).unique().named("ux_order_id"));
            reservations.createIndex(new Index()
                    .on("status", Direction.ASC).on("expiresAt", Direction.ASC).named("ix_status_expires_at"));
        };
    }
}
