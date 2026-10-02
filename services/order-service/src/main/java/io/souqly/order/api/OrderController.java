package io.souqly.order.api;

import java.net.URI;
import java.util.HashSet;
import java.util.List;

import io.souqly.order.api.ApiModels.CheckoutRequest;
import io.souqly.order.api.ApiModels.OrderResponse;
import io.souqly.order.checkout.OrderService;
import io.souqly.platform.security.Caller;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Checks out. The checkout runs while the request waits, so the answer is usually final:
     * {@code 201} with the order {@code CONFIRMED}, {@code REJECTED} or {@code CANCELLED}. If a
     * step has to be retried (the PSP timed out, say), the answer is {@code 202} and the order
     * completes in the background; poll its {@code Location}. Repeating a request with the same
     * {@code Idempotency-Key} returns the same order with {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    ResponseEntity<OrderResponse> checkout(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request, Authentication authentication) {
        if (!idempotencyKey.matches(ApiModels.IDENTIFIER)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-64 letters, digits, '.', '_' or '-'");
        }
        var skus = new HashSet<String>();
        if (!request.items().stream().allMatch(line -> skus.add(line.sku()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each SKU may appear only once per order");
        }
        var placement = orders.place(Caller.from(authentication), idempotencyKey, request.toItems(),
                request.paymentMethod());
        var body = OrderResponse.from(placement.order());
        var location = URI.create("/api/v1/orders/" + body.id());
        if (!placement.created()) {
            return ResponseEntity.ok().location(location).header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.status(body.completed() ? HttpStatus.CREATED : HttpStatus.ACCEPTED)
                .location(location).body(body);
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable String id, Authentication authentication) {
        return OrderResponse.from(orders.get(id, Caller.from(authentication)));
    }

    /** The caller's 20 most recent orders. */
    @GetMapping
    List<OrderResponse> mine(Authentication authentication) {
        return orders.recent(Caller.from(authentication), 20).stream().map(OrderResponse::from).toList();
    }
}
