package io.souqly.order.saga;

import io.souqly.order.order.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Continues orders that are not finished: steps waiting for a retry, and orders whose instance
 * died mid-checkout (their lease has expired). Runs on every instance; leases ensure each order
 * is advanced by one instance at a time.
 */
@Component
class SagaRecovery {

    private static final Logger log = LoggerFactory.getLogger(SagaRecovery.class);

    private final OrderStore orders;
    private final CheckoutSaga saga;

    SagaRecovery(OrderStore orders, CheckoutSaga saga) {
        this.orders = orders;
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${souqly.order.saga.recovery-interval:PT2S}")
    void resumeDueOrders() {
        for (String orderId : orders.due(50)) {
            try {
                saga.advance(orderId);
            }
            catch (RuntimeException ex) {
                log.warn("Could not advance order {}: {}", orderId, ex.getMessage());
            }
        }
    }
}
