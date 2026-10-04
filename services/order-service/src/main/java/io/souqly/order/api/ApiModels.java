package io.souqly.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.souqly.order.checkout.OrderService.Item;
import io.souqly.order.order.Failure;
import io.souqly.order.order.Order;
import io.souqly.order.order.OrderStatus;
import io.souqly.order.order.StatusChange;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class ApiModels {

    static final String IDENTIFIER = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    private ApiModels() {
    }

    /**
     * @param paymentMethod a payment-method token issued by the PSP to the buyer's browser; card
     *                      details never reach this service
     */
    record CheckoutRequest(
            @NotEmpty @Size(max = 20) List<@Valid @NotNull Line> items,
            @NotBlank @Size(max = 100) @Pattern(regexp = IDENTIFIER) String paymentMethod) {

        record Line(@NotNull @Pattern(regexp = IDENTIFIER) String sku, @Min(1) @Max(10) int quantity) {
        }

        List<Item> toItems() {
            return items.stream().map(line -> new Item(line.sku(), line.quantity())).toList();
        }
    }

    record OrderResponse(
            String id,
            OrderStatus status,
            boolean completed,
            List<LineResponse> lines,
            BigDecimal total,
            String currency,
            Failure failure,
            List<StatusChange> history,
            Instant createdAt,
            Instant updatedAt) {

        record LineResponse(String sku, String productId, String sellerId, Map<String, String> title, int quantity,
                BigDecimal unitPrice, BigDecimal lineTotal) {
        }

        static OrderResponse from(Order order) {
            var lines = order.lines().stream()
                    .map(l -> new LineResponse(l.sku(), l.productId(), l.sellerId(), l.title(), l.quantity(),
                            l.unitPrice(), l.lineTotal()))
                    .toList();
            return new OrderResponse(order.id(), order.status(), order.status().terminal(), lines, order.total(),
                    order.currency(), order.failure(), order.history(), order.createdAt(), order.updatedAt());
        }
    }
}
