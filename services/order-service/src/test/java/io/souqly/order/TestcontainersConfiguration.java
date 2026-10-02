package io.souqly.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Real MongoDB and Kafka. The services this one calls over HTTP (Keycloak's token endpoint,
 * inventory and the PSP) are played by WireMock, so tests can make them slow, fail or refuse.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MongoDBContainer mongoContainer() {
        return new MongoDBContainer(DockerImageName.parse("mongo:8.0")).withReplicaSet();
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    }

    @Bean(destroyMethod = "stop")
    WireMockServer fakes() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar fakeEndpoints(WireMockServer fakes) {
        String base = fakes.baseUrl();
        return registry -> {
            registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", () -> base + "/token");
            registry.add("souqly.order.inventory.base-url", () -> base + "/inventory/api/v1");
            registry.add("souqly.order.payments.base-url", () -> base + "/psp");
            registry.add("souqly.order.payments.timeout", () -> "PT0.5S");
            registry.add("souqly.order.saga.retry-backoff", () -> "PT0.1S");
            registry.add("souqly.order.saga.max-retry-backoff", () -> "PT0.3S");
            registry.add("souqly.order.saga.max-attempts", () -> "4");
            registry.add("souqly.order.saga.recovery-interval", () -> "PT0.2S");
            registry.add("souqly.outbox.poll-interval", () -> "PT0.1S");
        };
    }
}
