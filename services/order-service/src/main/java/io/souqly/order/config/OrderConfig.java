package io.souqly.order.config;

import io.souqly.order.order.Order;
import io.souqly.order.pricing.ProductPrice;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class OrderConfig {

    /** Keyed by order ID and carrying the full order, so compaction keeps each order's latest state. */
    @Bean
    NewTopic ordersTopic(OrderProperties properties) {
        var topics = properties.topics();
        return TopicBuilder.name(topics.orders()).partitions(topics.partitions()).replicas(topics.replicas())
                .compact().build();
    }

    @Bean
    SmartInitializingSingleton orderSchemaInitializer(MongoTemplate mongo) {
        return () -> {
            // Collections must exist before the first multi-document transaction writes to them.
            for (Class<?> type : new Class<?>[] {Order.class, ProductPrice.class}) {
                if (!mongo.collectionExists(type)) {
                    mongo.createCollection(type);
                }
            }
            var orders = mongo.indexOps(Order.class);
            // Makes checkout idempotent even when the same request arrives twice at once.
            orders.createIndex(new Index().on("buyerId", Direction.ASC).on("idempotencyKey", Direction.ASC)
                    .unique().named("ux_buyer_idempotency_key"));
            orders.createIndex(new Index().on("buyerId", Direction.ASC).on("createdAt", Direction.DESC)
                    .named("ix_buyer_created"));
            // The recovery sweeper's query: unfinished orders by due time.
            orders.createIndex(new Index().on("status", Direction.ASC).on("nextAttemptAt", Direction.ASC)
                    .named("ix_status_next_attempt"));
        };
    }

    @Bean
    OpenAPI orderOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Souqly Order API")
                .version("v1")
                .description("Checkout and order history. Checkout is idempotent per Idempotency-Key."));
    }
}
