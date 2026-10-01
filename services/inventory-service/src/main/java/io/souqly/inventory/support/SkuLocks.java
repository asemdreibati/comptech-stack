package io.souqly.inventory.support;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * Serialises stock writes per SKU within this instance.
 *
 * <p>MongoDB aborts a transaction that writes a document another open transaction has
 * already written. When hundreds of buyers hit one SKU, optimistic retries alone keep
 * colliding until they give up. Queueing writers for the same SKU in-process removes those
 * collisions entirely on each instance, leaving only cross-instance conflicts for
 * {@link MongoTransactions} to retry. Lock striping bounds memory regardless of catalogue size.
 */
@Component
public class SkuLocks {

    public static final int STRIPES = 1024;
    static final Duration MAX_WAIT = Duration.ofSeconds(2);

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final Timer waitTimer;

    public SkuLocks(MeterRegistry meterRegistry) {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
        this.waitTimer = Timer.builder("souqly.inventory.sku_lock.wait")
                .description("Time spent queueing for a per-SKU write slot")
                .register(meterRegistry);
    }

    /**
     * Runs {@code work} while holding the locks for every SKU. Locks are taken in stripe order,
     * so two multi-SKU orders can never deadlock. Waiting longer than {@link #MAX_WAIT} sheds
     * the request rather than letting the queue grow without bound.
     */
    public <T> T withLocks(Collection<String> skus, Supplier<T> work) {
        List<ReentrantLock> locks = skus.stream()
                .mapToInt(SkuLocks::stripe)
                .distinct()
                .sorted()
                .mapToObj(i -> stripes[i])
                .toList();
        int acquired = 0;
        long started = System.nanoTime();
        try {
            for (ReentrantLock lock : locks) {
                long remaining = MAX_WAIT.toNanos() - (System.nanoTime() - started);
                if (!lock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                    throw new TransactionContentionException(0, null);
                }
                acquired++;
            }
            waitTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return work.get();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for SKU locks", ex);
        }
        finally {
            for (int i = acquired - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
        }
    }

    /** The stripe guarding a SKU; equal stripes mean the same lock. */
    public static int stripe(String sku) {
        return Math.floorMod(sku.hashCode() * 0x9E3779B9, STRIPES);
    }
}
