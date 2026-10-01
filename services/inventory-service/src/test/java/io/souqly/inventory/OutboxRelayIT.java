package io.souqly.inventory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.souqly.inventory.reservation.ReservationLine;
import io.souqly.inventory.reservation.ReservationService;
import io.souqly.inventory.stock.StockService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxRelayIT {

    private static final String TOPIC = "inventory.reservation-events.v1";

    @Autowired
    ReservationService reservations;

    @Autowired
    StockService stock;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    JsonMapper json;

    @Test
    void publishesEveryStateChangeInOrderKeyedByReservation() {
        String sku = "EVT-" + UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();
        stock.restock(sku, 5, TestCallers.OPERATIONS);
        var reservation = reservations.reserve(orderId, List.of(new ReservationLine(sku, 2))).reservation();
        reservations.confirm(reservation.id());

        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (reservation.id().equals(record.key())) {
                        received.add(record);
                    }
                });
                return received.size() >= 2;
            });
        }

        assertThat(received).extracting(r -> header(r, "eventType"))
                .containsExactly("ReservationCreated", "ReservationConfirmed");

        JsonNode created = json.readTree(received.getFirst().value());
        assertThat(created.get("eventId").asString()).isEqualTo(header(received.getFirst(), "eventId"));
        assertThat(created.get("orderId").asString()).isEqualTo(orderId);
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("lines").get(0).get("sku").asString()).isEqualTo(sku);
        assertThat(created.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
