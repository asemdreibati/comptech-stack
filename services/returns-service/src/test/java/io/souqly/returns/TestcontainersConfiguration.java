package io.souqly.returns;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** Real PostgreSQL and Kafka; WireMock plays the payment provider, so tests can make it fail. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    }

    @Bean(destroyMethod = "stop")
    WireMockServer psp() {
        var server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar returnsEndpoints(WireMockServer psp) {
        return registry -> {
            registry.add("souqly.returns.payments.base-url", psp::baseUrl);
            registry.add("souqly.returns.payments.timeout", () -> "PT0.5S");
            // Fail fast: a failing step becomes an incident after two quick retries.
            registry.add("camunda.bpm.generic-properties.properties.failed-job-retry-time-cycle", () -> "R2/PT1S");
        };
    }
}
