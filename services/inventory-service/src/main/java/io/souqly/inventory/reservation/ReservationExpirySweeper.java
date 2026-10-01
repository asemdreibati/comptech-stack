package io.souqly.inventory.reservation;

import java.time.Clock;

import io.souqly.inventory.config.InventoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Returns stock held by abandoned checkouts. Safe to run on every instance at once: each
 * expiry is a conditional PENDING-to-EXPIRED update, so exactly one instance wins per reservation.
 */
@Component
class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final MongoTemplate mongo;
    private final ReservationService reservations;
    private final InventoryProperties properties;
    private final Clock clock;

    ReservationExpirySweeper(MongoTemplate mongo, ReservationService reservations, InventoryProperties properties,
            Clock clock) {
        this.mongo = mongo;
        this.reservations = reservations;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${souqly.inventory.expiry-sweep-interval:PT5S}")
    void sweep() {
        var due = query(where("status").is(ReservationStatus.PENDING).and("expiresAt").lte(clock.instant()))
                .with(Sort.by("expiresAt"))
                .limit(properties.expirySweepBatchSize());
        due.fields().include("_id");
        for (Reservation reservation : mongo.find(due, Reservation.class)) {
            try {
                reservations.expire(reservation.id());
            }
            catch (RuntimeException ex) {
                log.warn("Failed to expire reservation {}: {}", reservation.id(), ex.getMessage());
            }
        }
    }
}
