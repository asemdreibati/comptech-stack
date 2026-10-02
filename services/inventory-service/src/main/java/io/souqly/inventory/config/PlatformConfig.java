package io.souqly.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class PlatformConfig {

    @Bean
    NewTopic reservationEventsTopic(InventoryProperties properties) {
        var topics = properties.topics();
        return TopicBuilder.name(topics.reservationEvents()).partitions(topics.partitions())
                .replicas(topics.replicas()).build();
    }

    @Bean
    NewTopic stockLevelsTopic(InventoryProperties properties) {
        var topics = properties.topics();
        // Compacted: consumers only ever need the latest level per SKU.
        return TopicBuilder.name(topics.stockLevels()).partitions(topics.partitions())
                .replicas(topics.replicas()).compact().build();
    }

    @Bean
    OpenAPI inventoryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Souqly Inventory API")
                .version("v1")
                .description("Stock levels, order reservations and the flash-sale admission gate."));
    }
}
