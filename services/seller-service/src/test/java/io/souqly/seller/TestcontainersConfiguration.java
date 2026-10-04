package io.souqly.seller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Everything real: PostgreSQL (workflow engine and applications), Kafka, Form.io with the
 * {@code seller-kyc} form, MinIO, and Keycloak with the Souqly realm, whose admin API grants
 * approved applicants their seller role.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String FORMIO_EMAIL = "admin@souqly.dev";
    static final String FORMIO_PASSWORD = "formio-admin-dev";
    static final String MINIO_USER = "souqly";
    static final String MINIO_PASSWORD = "souqly-minio-dev-secret";

    private final Network network = Network.newNetwork();

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

    @Bean
    MongoDBContainer formioMongoContainer() {
        return new MongoDBContainer(DockerImageName.parse("mongo:8.0")).withNetwork(network)
                .withNetworkAliases("mongo");
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
    @Qualifier("keycloak")
    GenericContainer<?> keycloakContainer() {
        return new GenericContainer<>(DockerImageName.parse("keycloak/keycloak:26.4"))
                .withCopyFileToContainer(MountableFile.forHostPath("../../infra/keycloak/souqly-realm.json"),
                        "/opt/keycloak/data/import/souqly-realm.json")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withCommand("start-dev", "--import-realm")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/souqly/.well-known/openid-configuration").forPort(8080)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    @Bean
    DynamicPropertyRegistrar sellerEndpoints(@Qualifier("formio") GenericContainer<?> formio,
            @Qualifier("minio") GenericContainer<?> minio, @Qualifier("keycloak") GenericContainer<?> keycloak) {
        String formioUrl = "http://" + formio.getHost() + ":" + formio.getMappedPort(3001);
        importKycForm(formioUrl);
        String s3 = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        String keycloakUrl = keycloakUrl(keycloak);
        return registry -> {
            registry.add("souqly.formio.base-url", () -> formioUrl);
            registry.add("souqly.formio.email", () -> FORMIO_EMAIL);
            registry.add("souqly.formio.password", () -> FORMIO_PASSWORD);
            registry.add("souqly.onboarding.storage.endpoint", () -> s3);
            registry.add("souqly.onboarding.storage.public-endpoint", () -> s3);
            registry.add("souqly.onboarding.storage.access-key", () -> MINIO_USER);
            registry.add("souqly.onboarding.storage.secret-key", () -> MINIO_PASSWORD);
            registry.add("souqly.onboarding.keycloak.admin-url", () -> keycloakUrl);
            registry.add("spring.security.oauth2.client.provider.keycloak.token-uri",
                    () -> keycloakUrl + "/realms/souqly/protocol/openid-connect/token");
            // Fail fast: a failing step becomes an incident after two quick retries.
            registry.add("camunda.bpm.generic-properties.properties.failed-job-retry-time-cycle", () -> "R2/PT1S");
        };
    }

    static String keycloakUrl(GenericContainer<?> keycloak) {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    /** The form the platform ships, loaded the way infra/formio/bootstrap.sh does. */
    private static void importKycForm(String formioUrl) {
        var http = HttpClient.newHttpClient();
        try {
            var login = http.send(HttpRequest.newBuilder(URI.create(formioUrl + "/admin/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"data": {"email": "%s", "password": "%s"}}""".formatted(FORMIO_EMAIL, FORMIO_PASSWORD)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            String token = login.headers().firstValue("x-jwt-token").orElseThrow();
            var created = http.send(HttpRequest.newBuilder(URI.create(formioUrl + "/form"))
                    .header("Content-Type", "application/json").header("x-jwt-token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Files.readString(Path.of("../../infra/formio/forms/seller-kyc.json"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (created.statusCode() != 201) {
                throw new IllegalStateException("Importing the seller-kyc form failed: " + created.body());
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
