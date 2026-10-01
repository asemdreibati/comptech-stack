package io.souqly.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import io.souqly.inventory.flashsale.FlashSaleService;
import io.souqly.inventory.reservation.Reservation;
import io.souqly.inventory.reservation.ReservationLine;
import io.souqly.inventory.reservation.ReservationService;
import io.souqly.inventory.reservation.ReservationStatus;
import io.souqly.inventory.stock.InsufficientStockException;
import io.souqly.inventory.stock.StockService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The core guarantee: however many buyers race for the last units, exactly the available
 * quantity is sold, and stock counters always match the reservations that exist.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrentReservationIT {

    private static final int STOCK = 100;
    private static final int BUYERS = 600;
    private static final int THREADS = 64;

    @Autowired
    ReservationService reservations;

    @Autowired
    StockService stock;

    @Autowired
    FlashSaleService flashSales;

    @Autowired
    MongoTemplate mongo;

    @Test
    void neverOversellsWithoutTheGate() throws Exception {
        String sku = "HOT-" + UUID.randomUUID();
        stock.restock(sku, STOCK, TestCallers.OPERATIONS);

        Map<String, AtomicInteger> outcomes = race(BUYERS, i -> List.of(new ReservationLine(sku, 1)));

        assertThat(outcomes.get("created")).hasValue(STOCK);
        assertThat(outcomes.get("insufficient")).hasValue(BUYERS - STOCK);
        assertStockMatchesReservations(sku, STOCK, 0);
    }

    @Test
    void neverOversellsWithTheGateArmed() throws Exception {
        String sku = "FLASH-" + UUID.randomUUID();
        stock.restock(sku, STOCK, TestCallers.OPERATIONS);
        flashSales.arm(sku, null);

        Map<String, AtomicInteger> outcomes = race(BUYERS, i -> List.of(new ReservationLine(sku, 1)));

        assertThat(outcomes.get("created")).hasValue(STOCK);
        assertThat(outcomes.get("insufficient")).hasValue(BUYERS - STOCK);
        assertThat(flashSales.remainingTokens(sku)).contains(0L);
        assertStockMatchesReservations(sku, STOCK, 0);
    }

    @Test
    void multiLineOrdersStayConsistentUnderContention() throws Exception {
        String a = "PAIR-A-" + UUID.randomUUID();
        String b = "PAIR-B-" + UUID.randomUUID();
        stock.restock(a, 40, TestCallers.OPERATIONS);
        stock.restock(b, 60, TestCallers.OPERATIONS);

        // Lines arrive in both orders; every order needs one A and two B, so B runs out first.
        Map<String, AtomicInteger> outcomes = race(300, i -> i % 2 == 0
                ? List.of(new ReservationLine(a, 1), new ReservationLine(b, 2))
                : List.of(new ReservationLine(b, 2), new ReservationLine(a, 1)));

        assertThat(outcomes.get("created")).hasValue(30);
        assertStockMatchesReservations(a, 30, 10);
        assertStockMatchesReservations(b, 60, 0);
    }

    @Test
    void concurrentRetriesOfOneOrderReserveOnce() throws Exception {
        String sku = "RETRY-" + UUID.randomUUID();
        String orderId = "order-" + UUID.randomUUID();
        stock.restock(sku, 10, TestCallers.OPERATIONS);

        Set<String> reservationIds = ConcurrentHashMap.newKeySet();
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    reservationIds.add(reservations.reserve(orderId, List.of(new ReservationLine(sku, 3)))
                            .reservation().id());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(reservationIds).hasSize(1);
        assertStockMatchesReservations(sku, 3, 7);
    }

    private interface Basket {
        List<ReservationLine> forBuyer(int buyer);
    }

    private Map<String, AtomicInteger> race(int buyers, Basket basket) throws Exception {
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        for (String outcome : List.of("created", "insufficient", "other")) {
            outcomes.put(outcome, new AtomicInteger());
        }
        Map<String, Integer> errors = new ConcurrentHashMap<>();
        var start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < buyers; i++) {
                int buyer = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        reservations.reserve("order-" + UUID.randomUUID(), basket.forBuyer(buyer));
                        outcomes.get("created").incrementAndGet();
                    }
                    catch (InsufficientStockException ex) {
                        outcomes.get("insufficient").incrementAndGet();
                    }
                    catch (RuntimeException ex) {
                        outcomes.get("other").incrementAndGet();
                        errors.merge(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 1, Integer::sum);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertThat(errors).as("unexpected failures").isEmpty();
        return outcomes;
    }

    private void assertStockMatchesReservations(String sku, long expectedReserved, long expectedAvailable) {
        var item = stock.get(sku);
        long reservedUnits = mongo.find(query(where("lines.sku").is(sku).and("status").is(ReservationStatus.PENDING)),
                        Reservation.class).stream()
                .flatMap(r -> r.lines().stream())
                .filter(line -> line.sku().equals(sku))
                .mapToLong(ReservationLine::quantity)
                .sum();
        assertThat(item.reserved()).isEqualTo(expectedReserved).isEqualTo(reservedUnits);
        assertThat(item.available()).isEqualTo(expectedAvailable);
    }
}
