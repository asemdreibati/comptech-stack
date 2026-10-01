package io.souqly.inventory.api;

import java.net.URI;
import java.util.HashSet;

import io.souqly.inventory.api.ApiModels.ReservationResponse;
import io.souqly.inventory.api.ApiModels.ReserveRequest;
import io.souqly.inventory.reservation.ReservationService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /**
     * Reserves stock for an order. Returns 201 when created, or 200 with
     * {@code Idempotent-Replayed: true} when the order already holds this reservation.
     */
    @PostMapping
    ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        var skus = new HashSet<String>();
        if (!request.lines().stream().allMatch(line -> skus.add(line.sku()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each SKU may appear only once per reservation");
        }
        var result = reservations.reserve(request.orderId(), request.toLines());
        var body = ReservationResponse.from(result.reservation());
        if (!result.created()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/reservations/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    ReservationResponse get(@PathVariable String id) {
        return ReservationResponse.from(reservations.get(id));
    }

    @PostMapping("/{id}/confirm")
    ReservationResponse confirm(@PathVariable String id) {
        return ReservationResponse.from(reservations.confirm(id));
    }

    @PostMapping("/{id}/release")
    ReservationResponse release(@PathVariable String id) {
        return ReservationResponse.from(reservations.release(id));
    }
}
