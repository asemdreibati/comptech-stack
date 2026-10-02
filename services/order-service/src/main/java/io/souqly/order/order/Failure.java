package io.souqly.order.order;

/** Why an order did not complete, as a stable code plus a human-readable message. */
public record Failure(String code, String message) {
}
