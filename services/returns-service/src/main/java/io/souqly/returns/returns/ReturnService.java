package io.souqly.returns.returns;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.souqly.platform.security.Caller;
import io.souqly.returns.config.ReturnsProperties;
import io.souqly.returns.orders.OrderReplica;
import io.souqly.returns.orders.PurchasedOrder;
import io.souqly.returns.returns.ReturnExceptions.ReturnNotFoundException;
import io.souqly.returns.returns.ReturnExceptions.ReturnRejectedException;
import io.souqly.returns.security.Permissions;
import io.souqly.returns.workflow.Returns;
import org.cibseven.bpm.engine.RuntimeService;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The buyer's side of returns: ask to return items, see where a return stands, and answer a
 * seller's rejection. Opening a return starts its workflow in the same transaction.
 */
@Service
public class ReturnService {

    public record Item(String sku, int quantity) {
    }

    /** @param created {@code false} when the request replayed an earlier one with the same key */
    public record Placement(ReturnRequest request, boolean created) {
    }

    public record Detail(ReturnRequest request, List<ReturnDecision> decisions) {
    }

    private final ReturnStore returns;
    private final OrderReplica orders;
    private final RuntimeService runtime;
    private final TransactionTemplate transaction;
    private final ReturnsProperties properties;
    private final Clock clock;

    public ReturnService(ReturnStore returns, OrderReplica orders, RuntimeService runtime,
            TransactionTemplate transaction, ReturnsProperties properties, Clock clock) {
        this.returns = returns;
        this.orders = orders;
        this.runtime = runtime;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    public Placement request(Caller buyer, String idempotencyKey, String orderId, ReturnReason reason, String comment,
            List<Item> items) {
        String hash = fingerprint(orderId, reason, items);
        var existing = returns.findByKey(buyer.subject(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, hash);
        }
        try {
            return new Placement(transaction.execute(status -> open(buyer, idempotencyKey, hash, orderId, reason,
                    comment, items)), true);
        }
        catch (DuplicateKeyException raced) {
            return replay(returns.findByKey(buyer.subject(), idempotencyKey).orElseThrow(), idempotencyKey, hash);
        }
    }

    public Detail get(UUID id, Caller caller) {
        ReturnRequest request = returns.find(id)
                .filter(candidate -> canSee(candidate, caller))
                .orElseThrow(() -> new ReturnNotFoundException(id));
        return new Detail(request, returns.decisions(id));
    }

    public List<ReturnRequest> mine(Caller buyer) {
        return returns.forBuyer(buyer.subject());
    }

    private ReturnRequest open(Caller buyer, String key, String hash, String orderId, ReturnReason reason,
            String comment, List<Item> items) {
        // Locked until commit: concurrent requests for this order are checked one at a time.
        PurchasedOrder order = orders.lock(orderId)
                .filter(candidate -> candidate.buyerId().equals(buyer.subject()))
                .orElseThrow(() -> new ReturnRejectedException("ORDER_NOT_FOUND", "Order " + orderId + " not found"));
        if (!order.confirmed() || order.confirmedAt() == null || order.paymentId() == null) {
            throw new ReturnRejectedException("ORDER_NOT_RETURNABLE", "Only confirmed orders can be returned");
        }
        Instant closes = order.confirmedAt().plus(properties.window());
        if (clock.instant().isAfter(closes)) {
            throw new ReturnRejectedException("RETURN_WINDOW_CLOSED", "Returns for this order closed at " + closes);
        }

        Map<String, PurchasedOrder.Line> bought = order.lines().stream()
                .collect(Collectors.toMap(PurchasedOrder.Line::sku, line -> line));
        Map<String, Integer> alreadyReturned = returns.quantitiesInReturns(orderId);
        List<ReturnRequest.Line> lines = new ArrayList<>();
        Set<String> sellers = new HashSet<>();
        for (Item item : items) {
            PurchasedOrder.Line line = bought.get(item.sku());
            if (line == null) {
                throw new ReturnRejectedException("ITEM_NOT_IN_ORDER", item.sku() + " is not in order " + orderId);
            }
            int left = line.quantity() - alreadyReturned.getOrDefault(item.sku(), 0);
            if (item.quantity() > left) {
                throw new ReturnRejectedException("QUANTITY_EXCEEDED",
                        "Only " + left + " of " + item.sku() + " can still be returned");
            }
            sellers.add(line.sellerId());
            lines.add(new ReturnRequest.Line(item.sku(), line.title(), item.quantity(), line.unitPrice()));
        }
        if (sellers.size() != 1) {
            throw new ReturnRejectedException("MIXED_SELLERS",
                    "Items from different sellers are returned separately, one return per seller");
        }
        BigDecimal refund = lines.stream()
                .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Instant now = clock.instant();
        String sellerId = sellers.iterator().next();
        var request = new ReturnRequest(UUID.randomUUID(), orderId, buyer.subject(), sellerId, key, hash, reason,
                comment, List.copyOf(lines), refund, order.currency(), ReturnStatus.REQUESTED, null, 0, now, now,
                null);
        returns.insert(request);
        Map<String, Object> variables = new HashMap<>();
        variables.put(Returns.RETURN_ID, request.id().toString());
        variables.put(Returns.BUYER_ID, buyer.subject());
        variables.put(Returns.SELLER_ID, sellerId);
        variables.put(Returns.REASON, reason.name());
        variables.put(Returns.REFUND_AMOUNT, refund.doubleValue());
        variables.put(Returns.CURRENCY, order.currency());
        variables.put(Returns.SELLER_SLA, properties.sellerSla().toString());
        variables.put(Returns.DISPUTE_WINDOW, properties.disputeWindow().toString());
        variables.put(Returns.SHIPPING_WINDOW, properties.shippingWindow().toString());
        runtime.startProcessInstanceByKey(Returns.PROCESS_KEY, request.id().toString(), variables);
        return request;
    }

    private static Placement replay(ReturnRequest existing, String key, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new ReturnRejectedException("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key " + key + " was already used for a different return");
        }
        return new Placement(existing, false);
    }

    static boolean canSee(ReturnRequest request, Caller caller) {
        return request.buyerId().equals(caller.subject())
                || caller.has(Permissions.RETURN_DECIDE) && request.sellerId().equals(caller.sellerId())
                || caller.has(Permissions.RETURN_ARBITRATE)
                || caller.has(Permissions.RETURN_HANDLE);
    }

    static String fingerprint(String orderId, ReturnReason reason, List<Item> items) {
        String canonical = orderId + "|" + reason + "|" + items.stream()
                .sorted(Comparator.comparing(Item::sku))
                .map(item -> item.sku() + "=" + item.quantity())
                .collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
