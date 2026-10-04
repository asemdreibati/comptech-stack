package io.souqly.order.checkout;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.souqly.order.checkout.CheckoutExceptions.IdempotencyKeyReusedException;
import io.souqly.order.checkout.CheckoutExceptions.MixedCurrencyException;
import io.souqly.order.checkout.CheckoutExceptions.OrderNotFoundException;
import io.souqly.order.checkout.CheckoutExceptions.ProductUnavailableException;
import io.souqly.order.order.Order;
import io.souqly.order.order.OrderLine;
import io.souqly.order.order.OrderStatus;
import io.souqly.order.order.OrderStore;
import io.souqly.order.order.StatusChange;
import io.souqly.platform.payments.PaymentGateway;
import io.souqly.order.pricing.PriceBook;
import io.souqly.order.pricing.ProductPrice;
import io.souqly.order.saga.CheckoutSaga;
import io.souqly.platform.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import static io.souqly.order.security.Permissions.ORDER_READ_ANY;

/**
 * Places orders and lets buyers read them.
 *
 * <p>Checkout is idempotent per buyer and {@code Idempotency-Key}: a client that times out and
 * retries gets the order it already placed, never a second one. The key is bound to a fingerprint
 * of the request, so reusing it for a different basket is refused rather than silently answered
 * with the wrong order.
 */
@Service
public class OrderService {

    public record Item(String sku, int quantity) {
    }

    /** @param created {@code false} when the request replayed an earlier checkout */
    public record Placement(Order order, boolean created) {
    }

    /**
     * How long the sweeper leaves a new order to the request that placed it. The request advances
     * the saga inline; the sweeper only steps in if that instance dies or a step must be retried.
     */
    static final Duration INLINE_GRACE = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderStore orders;
    private final PriceBook prices;
    private final CheckoutSaga saga;
    private final Clock clock;

    public OrderService(OrderStore orders, PriceBook prices, CheckoutSaga saga, Clock clock) {
        this.orders = orders;
        this.prices = prices;
        this.saga = saga;
        this.clock = clock;
    }

    public Placement place(Caller buyer, String idempotencyKey, List<Item> items, String paymentMethod) {
        String hash = fingerprint(items, paymentMethod);
        Order existing = orders.findByKey(buyer.subject(), idempotencyKey);
        if (existing != null) {
            return replay(existing, idempotencyKey, hash);
        }
        Order order = newOrder(buyer, idempotencyKey, hash, items, paymentMethod);
        try {
            orders.insert(order);
        }
        catch (DuplicateKeyException raced) {
            // The same request arrived twice at once; the other one created the order.
            return replay(orders.findByKey(buyer.subject(), idempotencyKey), idempotencyKey, hash);
        }
        try {
            return new Placement(saga.advance(order.id()), true);
        }
        catch (RuntimeException ex) {
            // The order is stored; the recovery sweeper will carry on from its persisted status.
            log.warn("Checkout of order {} interrupted, leaving it to recovery: {}", order.id(), ex.getMessage());
            return new Placement(orders.get(order.id()), true);
        }
    }

    public Order get(String id, Caller caller) {
        Order order = orders.get(id);
        // Someone else's order is reported as missing, not forbidden, so IDs cannot be probed.
        if (order == null || !(caller.subject().equals(order.buyerId()) || caller.has(ORDER_READ_ANY))) {
            throw new OrderNotFoundException(id);
        }
        return order;
    }

    public List<Order> recent(Caller buyer, int limit) {
        return orders.recentFor(buyer.subject(), limit);
    }

    private Placement replay(Order existing, String key, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        return new Placement(existing, false);
    }

    private Order newOrder(Caller buyer, String key, String hash, List<Item> items, String paymentMethod) {
        Map<String, ProductPrice> priced = prices.find(items.stream().map(Item::sku).toList()).stream()
                .collect(Collectors.toMap(ProductPrice::sku, Function.identity()));
        List<String> unavailable = items.stream().map(Item::sku)
                .filter(sku -> !payable(priced.get(sku)))
                .sorted()
                .toList();
        if (!unavailable.isEmpty()) {
            throw new ProductUnavailableException(unavailable);
        }
        List<String> currencies = priced.values().stream().map(ProductPrice::currency).distinct().sorted().toList();
        if (currencies.size() > 1) {
            throw new MixedCurrencyException(currencies);
        }
        List<OrderLine> lines = items.stream()
                .map(item -> {
                    ProductPrice price = priced.get(item.sku());
                    return new OrderLine(item.sku(), price.productId(), price.sellerId(), price.title(),
                            item.quantity(), price.amount());
                })
                .toList();
        BigDecimal total = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant now = clock.instant();
        return new Order(UUID.randomUUID().toString(), buyer.subject(), key, hash, lines, total, currencies.getFirst(),
                paymentMethod, null, null, OrderStatus.PLACED, null,
                List.of(new StatusChange(OrderStatus.PLACED, now, "order placed")),
                0, null, now.plus(INLINE_GRACE), null, 0, now, now);
    }

    /** Live, and priced in a real currency with no more decimals than that currency has. */
    private static boolean payable(ProductPrice price) {
        if (price == null || !price.purchasable() || price.amount() == null || price.amount().signum() <= 0
                || price.currency() == null) {
            return false;
        }
        try {
            PaymentGateway.minorUnits(price.amount(), price.currency());
            return true;
        }
        catch (IllegalArgumentException | ArithmeticException ex) {
            return false;
        }
    }

    /** The same basket and payment method give the same fingerprint, whatever the item order. */
    static String fingerprint(List<Item> items, String paymentMethod) {
        String canonical = items.stream()
                .sorted(Comparator.comparing(Item::sku))
                .map(item -> item.sku() + "=" + item.quantity())
                .collect(Collectors.joining(",")) + "|" + paymentMethod;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
