package io.souqly.inventory.config;

import java.time.Clock;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class PlatformConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic reservationEventsTopic(InventoryProperties properties) {
        var outbox = properties.outbox();
        return TopicBuilder.name(outbox.topic())
                .partitions(outbox.topicPartitions())
                .replicas(outbox.topicReplicas())
                .build();
    }

    @Bean
    OpenAPI inventoryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Souqly Inventory API")
                .version("v1")
                .description("Stock levels, order reservations and the flash-sale admission gate."));
    }
}
