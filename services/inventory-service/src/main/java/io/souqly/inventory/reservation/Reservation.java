package io.souqly.inventory.reservation;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stock held for one order. {@code orderId} is unique, which makes reserving idempotent:
 * a retried request finds the reservation its first attempt created.
 */
@Document("reservations")
public record Reservation(
        @Id String id,
        String orderId,
        List<ReservationLine> lines,
        ReservationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant updatedAt) {
}
