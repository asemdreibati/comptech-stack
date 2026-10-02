package io.souqly.catalog.config;

import io.souqly.catalog.category.Category;
import io.souqly.catalog.product.Product;
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
class CatalogConfig {

    /** Compacted: the latest state of every listing stays on the topic, so search can rebuild from it. */
    @Bean
    NewTopic productsTopic(CatalogProperties properties) {
        var topics = properties.topics();
        return TopicBuilder.name(topics.products()).partitions(topics.partitions()).replicas(topics.replicas())
                .compact().build();
    }

    @Bean
    SmartInitializingSingleton catalogSchemaInitializer(MongoTemplate mongo) {
        return () -> {
            for (Class<?> type : new Class<?>[] {Category.class, Product.class}) {
                if (!mongo.collectionExists(type)) {
                    mongo.createCollection(type);
                }
            }
            var products = mongo.indexOps(Product.class);
            products.createIndex(new Index().on("sku", Direction.ASC).unique().named("ux_sku"));
            products.createIndex(new Index().on("sellerId", Direction.ASC).on("updatedAt", Direction.DESC)
                    .named("ix_seller_updated"));
        };
    }

    @Bean
    OpenAPI catalogOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Souqly Catalog API")
                .version("v1")
                .description("Categories, seller listings and listing images."));
    }
}
