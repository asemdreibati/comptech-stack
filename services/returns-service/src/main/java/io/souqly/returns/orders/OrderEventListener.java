package io.souqly.returns.orders;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Keeps the order replica in step with the order service's events. */
@Component
class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderReplica orders;
    private final JsonMapper json;

    OrderEventListener(OrderReplica orders, JsonMapper json) {
        this.orders = orders;
        this.json = json;
    }

    @KafkaListener(id = "returns-order-replica", topics = "${souqly.returns.topics.orders}")
    void onOrderEvent(String payload) {
        JsonNode event;
        try {
            event = json.readTree(payload);
        }
        catch (JacksonException ex) {
            log.warn("Skipping unreadable order event: {}", ex.getMessage());
            return;
        }
        if (!event.hasNonNull("orderId") || !event.hasNonNull("buyerId")) {
            return;
        }
        String status = event.path("status").asString();
        orders.apply(new PurchasedOrder(
                event.get("orderId").asString(),
                event.get("buyerId").asString(),
                status,
                event.path("currency").asString(),
                event.path("paymentId").asString(null),
                json.convertValue(event.path("lines"), new TypeReference<List<PurchasedOrder.Line>>() { }),
                "CONFIRMED".equals(status) ? Instant.parse(event.path("occurredAt").asString()) : null,
                event.path("version").asLong()));
    }
}
