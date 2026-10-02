package io.souqly.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Real MongoDB, Kafka, Form.io (with the category forms from {@code infra/formio/forms}) and
 * MinIO. Form.io keeps its data in the same MongoDB container, in its own database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String MINIO_USER = "souqly";
    static final String MINIO_PASSWORD = "souqly-minio-dev-secret";
    static final String FORMIO_EMAIL = "admin@souqly.dev";
    static final String FORMIO_PASSWORD = "formio-admin-dev";

    private final Network network = Network.newNetwork();

    @Bean
    @ServiceConnection
    MongoDBContainer mongoContainer() {
        return new MongoDBContainer(DockerImageName.parse("mongo:8.0")).withReplicaSet()
                .withNetwork(network).withNetworkAliases("mongo");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
    }

    @Bean
    @Qualifier("formio")
    GenericContainer<?> formioContainer(MongoDBContainer mongo) {
        if (!mongo.isRunning()) {
            mongo.start();
        }
        return new GenericContainer<>(DockerImageName.parse("formio/formio:latest"))
                .withNetwork(network)
                .withEnv("NODE_CONFIG", """
                        {"mongo": "mongodb://mongo:27017/formio?directConnection=true",
                         "mongoSecret": "test-mongo-secret", "jwt": {"secret": "test-jwt-secret", "expireTime": 240}}""")
                .withEnv("ROOT_EMAIL", FORMIO_EMAIL)
                .withEnv("ROOT_PASSWORD", FORMIO_PASSWORD)
                .withExposedPorts(3001)
                // Form.io CE answers 400 on /health; /access returns 200 once it has finished installing.
                .waitingFor(Wait.forHttp("/access").forPort(3001).withStartupTimeout(Duration.ofMinutes(3)));
    }

    @Bean
    @Qualifier("minio")
    GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse("bitnamilegacy/minio:latest"))
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Bean
    DynamicPropertyRegistrar catalogEndpoints(@Qualifier("formio") GenericContainer<?> formio,
            @Qualifier("minio") GenericContainer<?> minio) {
        String formioUrl = "http://" + formio.getHost() + ":" + formio.getMappedPort(3001);
        importForms(formioUrl);
        String s3 = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        return registry -> {
            registry.add("souqly.catalog.formio.base-url", () -> formioUrl);
            registry.add("souqly.catalog.formio.email", () -> FORMIO_EMAIL);
            registry.add("souqly.catalog.formio.password", () -> FORMIO_PASSWORD);
            registry.add("souqly.catalog.storage.endpoint", () -> s3);
            registry.add("souqly.catalog.storage.public-endpoint", () -> s3);
            registry.add("souqly.catalog.storage.public-base-url", () -> s3 + "/souqly-product-images");
            registry.add("souqly.catalog.storage.access-key", () -> MINIO_USER);
            registry.add("souqly.catalog.storage.secret-key", () -> MINIO_PASSWORD);
        };
    }

    /** The same forms the platform ships, loaded the way infra/formio/bootstrap.sh does. */
    private static void importForms(String formioUrl) {
        var http = HttpClient.newHttpClient();
        try {
            var login = http.send(HttpRequest.newBuilder(URI.create(formioUrl + "/admin/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"data": {"email": "%s", "password": "%s"}}""".formatted(FORMIO_EMAIL, FORMIO_PASSWORD)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            String token = login.headers().firstValue("x-jwt-token").orElseThrow();
            for (Path form : List.of(Path.of("../../infra/formio/forms/category-phones.json"),
                    Path.of("../../infra/formio/forms/category-fashion.json"))) {
                var created = http.send(HttpRequest.newBuilder(URI.create(formioUrl + "/form"))
                        .header("Content-Type", "application/json").header("x-jwt-token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(Files.readString(form))).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (created.statusCode() != 201) {
                    throw new IllegalStateException("Importing " + form + " failed: " + created.body());
                }
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
