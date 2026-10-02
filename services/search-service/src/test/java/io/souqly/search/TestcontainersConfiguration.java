package io.souqly.search;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Real Kafka and a single-node OpenSearch (security plugin off, as in local development). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    }

    @Bean
    @Qualifier("opensearch")
    GenericContainer<?> opensearchContainer() {
        return new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));
    }

    @Bean
    DynamicPropertyRegistrar opensearchUrl(@Qualifier("opensearch") GenericContainer<?> opensearch) {
        String url = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);
        return registry -> {
            registry.add("souqly.search.opensearch.url", () -> url);
            registry.add("souqly.search.retry.max-elapsed", () -> "PT2S");
        };
    }
}
