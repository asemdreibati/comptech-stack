package io.souqly.order.order;

import java.time.Instant;

public record StatusChange(OrderStatus status, Instant at, String note) {
}
