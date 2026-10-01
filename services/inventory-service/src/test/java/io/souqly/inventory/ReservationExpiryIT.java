package io.souqly.inventory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.souqly.inventory.flashsale.FlashSaleService;
import io.souqly.inventory.reservation.ReservationLine;
import io.souqly.inventory.reservation.ReservationService;
import io.souqly.inventory.reservation.ReservationStateException;
import io.souqly.inventory.reservation.ReservationStatus;
import io.souqly.inventory.stock.StockService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "souqly.inventory.reservation-ttl=PT1S",
        "souqly.inventory.expiry-sweep-interval=PT0.2S"
})
@Import(TestcontainersConfiguration.class)
class ReservationExpiryIT {

    @Autowired
    ReservationService reservations;

    @Autowired
    StockService stock;

    @Autowired
    FlashSaleService flashSales;

    @Test
    void abandonedReservationsReturnStockAndGateTokens() {
        String sku = "EXP-" + UUID.randomUUID();
        stock.restock(sku, 2, TestCallers.OPERATIONS);
        flashSales.arm(sku, 2L);

        var reservation = reservations.reserve("order-" + UUID.randomUUID(), List.of(new ReservationLine(sku, 2)))
                .reservation();
        assertThat(stock.get(sku).available()).isZero();
        assertThat(flashSales.remainingTokens(sku)).contains(0L);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(reservations.get(reservation.id()).status()).isEqualTo(ReservationStatus.EXPIRED));

        assertThat(stock.get(sku).available()).isEqualTo(2);
        assertThat(stock.get(sku).reserved()).isZero();
        assertThat(flashSales.remainingTokens(sku)).contains(2L);

        assertThatExceptionOfType(ReservationStateException.class)
                .isThrownBy(() -> reservations.confirm(reservation.id()))
                .satisfies(ex -> assertThat(ex.current()).isEqualTo(ReservationStatus.EXPIRED));
    }
}
