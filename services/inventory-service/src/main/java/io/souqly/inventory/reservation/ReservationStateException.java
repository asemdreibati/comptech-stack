package io.souqly.inventory.reservation;

/** The requested transition is not allowed from the reservation's current state. */
public class ReservationStateException extends RuntimeException {

    private final ReservationStatus current;
    private final boolean expired;

    private ReservationStateException(String message, ReservationStatus current, boolean expired) {
        super(message);
        this.current = current;
        this.expired = expired;
    }

    static ReservationStateException illegalTransition(Reservation reservation, ReservationStatus target) {
        return new ReservationStateException("Reservation %s is %s and cannot become %s"
                .formatted(reservation.id(), reservation.status(), target), reservation.status(), false);
    }

    static ReservationStateException expired(Reservation reservation) {
        return new ReservationStateException("Reservation %s expired at %s"
                .formatted(reservation.id(), reservation.expiresAt()), reservation.status(), true);
    }

    public ReservationStatus current() {
        return current;
    }

    public boolean expired() {
        return expired;
    }
}
