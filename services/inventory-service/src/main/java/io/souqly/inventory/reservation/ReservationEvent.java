package io.souqly.inventory.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.souqly.inventory.outbox.OutboxWriter;

/**
 * Published to Kafka for every reservation state change. Consumers deduplicate on
 * {@code eventId}, since the outbox delivers at least once.
 */
public record ReservationEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String reservationId,
        String orderId,
        ReservationStatus status,
        List<ReservationLine> lines,
        Instant expiresAt) {

    public static final String AGGREGATE_TYPE = "Reservation";

    /** An outbox message describing the reservation's current state. */
    static OutboxWriter.Message messageFor(Reservation reservation, Instant occurredAt) {
        String eventId = UUID.randomUUID().toString();
        String eventType = typeFor(reservation.status());
        var event = new ReservationEvent(eventId, eventType, occurredAt, reservation.id(), reservation.orderId(),
                reservation.status(), reservation.lines(), reservation.expiresAt());
        return new OutboxWriter.Message(eventId, AGGREGATE_TYPE, reservation.id(), eventType, occurredAt, event);
    }

    static String typeFor(ReservationStatus status) {
        return switch (status) {
            case PENDING -> "ReservationCreated";
            case CONFIRMED -> "ReservationConfirmed";
            case RELEASED -> "ReservationReleased";
            case EXPIRED -> "ReservationExpired";
        };
    }
}
