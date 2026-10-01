package io.souqly.inventory.reservation;

/** The order ID was already used for a reservation with different lines. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String orderId) {
        super("Order " + orderId + " already has a reservation with different lines");
    }
}
