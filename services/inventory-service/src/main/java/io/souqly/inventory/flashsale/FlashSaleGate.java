package io.souqly.inventory.flashsale;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.souqly.inventory.config.InventoryProperties;
import io.souqly.inventory.reservation.ReservationLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis admission gate that sheds flash-sale traffic before it reaches MongoDB.
 *
 * <p>When a sale is armed for a SKU, each order line must claim tokens here first; once the
 * tokens run out, requests are rejected in Redis without opening a MongoDB transaction. The
 * gate is an optimisation, never the source of truth: MongoDB's conditional update still
 * guards every unit, so a lost, stale or unreachable gate can only cause extra MongoDB load
 * or a false "sold out", never an oversell. On Redis errors the gate therefore fails open.
 *
 * <p>Keys use a {@code {sku}} hash tag so a SKU's counter and claims share a cluster slot.
 */
@Component
public class FlashSaleGate {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleGate.class);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List<Long>> CLAIM =
            (RedisScript) RedisScript.of(new ClassPathResource("redis/gate-claim.lua"), List.class);
    private static final List<Long> NOT_ARMED = List.of(-1L, 0L);
    private static final RedisScript<Long> REFUND =
            RedisScript.of(new ClassPathResource("redis/gate-refund.lua"), Long.class);
    private static final RedisScript<Long> ADD =
            RedisScript.of(new ClassPathResource("redis/gate-add.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final String claimTtlSeconds;
    private final Counter errors;

    public FlashSaleGate(StringRedisTemplate redis, InventoryProperties properties, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.claimTtlSeconds = Long.toString(properties.flashSale().claimTtl().toSeconds());
        this.errors = Counter.builder("souqly.inventory.gate.errors")
                .description("Redis failures in the flash-sale gate (gate failed open)")
                .register(meterRegistry);
    }

    public void arm(String sku, long tokens) {
        redis.opsForValue().set(tokensKey(sku), Long.toString(tokens));
    }

    public void disarm(String sku) {
        redis.delete(tokensKey(sku));
    }

    public Optional<Long> remainingTokens(String sku) {
        return Optional.ofNullable(redis.opsForValue().get(tokensKey(sku))).map(Long::valueOf);
    }

    /**
     * Claims tokens for every line of an order. If any line is sold out, the lines newly
     * claimed by this call are refunded and the sold-out line is reported. Lines an earlier
     * attempt of the same order already claimed are admitted without taking more tokens.
     */
    public GateDecision claim(String orderId, List<ReservationLine> lines) {
        List<ReservationLine> newlyClaimed = new ArrayList<>(lines.size());
        for (ReservationLine line : lines) {
            List<Long> result = failOpen(() -> redis.execute(CLAIM,
                    List.of(tokensKey(line.sku()), claimKey(line.sku(), orderId)),
                    Integer.toString(line.quantity()), claimTtlSeconds), NOT_ARMED);
            long code = result.get(0);
            if (code == 0) {
                refund(orderId, newlyClaimed);
                return GateDecision.reject(line.sku(), result.get(1));
            }
            if (code == 1) {
                newlyClaimed.add(line);
            }
        }
        return GateDecision.admit(newlyClaimed);
    }

    /** Returns an order's claimed tokens to the pool. Safe to call repeatedly. */
    public void refund(String orderId, List<ReservationLine> lines) {
        for (ReservationLine line : lines) {
            failOpen(() -> redis.execute(REFUND,
                    List.of(tokensKey(line.sku()), claimKey(line.sku(), orderId))), 0L);
        }
    }

    public void addTokensIfArmed(String sku, long quantity) {
        failOpen(() -> redis.execute(ADD, List.of(tokensKey(sku)), Long.toString(quantity)), -1L);
    }

    private <T> T failOpen(Supplier<T> call, T fallback) {
        try {
            T result = call.get();
            return result != null ? result : fallback;
        }
        catch (DataAccessException ex) {
            errors.increment();
            log.warn("Flash-sale gate unavailable, failing open: {}", ex.getMessage());
            return fallback;
        }
    }

    static String tokensKey(String sku) {
        return "inv:gate:{" + sku + "}:tokens";
    }

    static String claimKey(String sku, String orderId) {
        return "inv:gate:{" + sku + "}:claim:" + orderId;
    }
}
