package io.souqly.inventory.reservation;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(String reservationId) {
        super("Reservation " + reservationId + " not found");
    }
}
