package io.souqly.inventory.api;

import java.time.Instant;
import java.util.List;

import io.souqly.inventory.reservation.Reservation;
import io.souqly.inventory.reservation.ReservationLine;
import io.souqly.inventory.reservation.ReservationStatus;
import io.souqly.inventory.stock.StockItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the public API, kept apart from the persistence model. */
final class ApiModels {

    /** SKUs and order IDs end up in Redis keys and log lines, so their alphabet is restricted. */
    static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    private ApiModels() {
    }

    record RestockRequest(@NotNull @Positive @Max(1_000_000) Long quantity) {
    }

    record StockResponse(String sku, long available, long reserved, Instant updatedAt) {

        static StockResponse from(StockItem item) {
            return new StockResponse(item.sku(), item.available(), item.reserved(), item.updatedAt());
        }
    }

    record ReserveRequest(
            @NotNull @Pattern(regexp = IDENTIFIER) String orderId,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid Line> lines) {

        record Line(
                @NotNull @Pattern(regexp = IDENTIFIER) String sku,
                @Min(1) @Max(1_000) int quantity) {
        }

        List<ReservationLine> toLines() {
            return lines.stream().map(l -> new ReservationLine(l.sku(), l.quantity())).toList();
        }
    }

    record ReservationResponse(
            String id,
            String orderId,
            ReservationStatus status,
            List<ReservationLine> lines,
            Instant createdAt,
            Instant expiresAt) {

        static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.id(), r.orderId(), r.status(), r.lines(), r.createdAt(), r.expiresAt());
        }
    }

    record ArmFlashSaleRequest(@PositiveOrZero Long tokens) {
    }

    record FlashSaleResponse(String sku, long remainingTokens) {
    }
}
