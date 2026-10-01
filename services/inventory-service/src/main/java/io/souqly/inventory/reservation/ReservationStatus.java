package io.souqly.inventory.reservation;

/**
 * PENDING is the only non-terminal state: it moves to CONFIRMED when the order is paid,
 * RELEASED when the order is cancelled, or EXPIRED when nobody acts before the deadline.
 */
public enum ReservationStatus {
    PENDING, CONFIRMED, RELEASED, EXPIRED
}
