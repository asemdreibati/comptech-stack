package io.souqly.inventory.support;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.mongodb.MongoException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a MongoDB transaction and retries it when the server labels the failure as
 * transient (typically a write conflict on a hot stock document). Callers must keep the
 * work free of side effects outside MongoDB, because it may run more than once.
 */
@Component
public class MongoTransactions {

    static final int MAX_ATTEMPTS = 12;
    private static final long BASE_BACKOFF_MILLIS = 2;
    private static final long MAX_BACKOFF_MILLIS = 200;

    private final TransactionTemplate template;
    private final Counter retries;

    public MongoTransactions(MongoTransactionManager transactionManager, MeterRegistry meterRegistry) {
        this.template = new TransactionTemplate(transactionManager);
        this.retries = Counter.builder("souqly.inventory.tx.retries")
                .description("MongoDB transactions retried after a transient error")
                .register(meterRegistry);
    }

    public <T> T execute(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return template.execute(status -> work.get());
            }
            catch (RuntimeException ex) {
                if (!isTransient(ex)) {
                    throw ex;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new TransactionContentionException(attempt, ex);
                }
                retries.increment();
                backoff(attempt);
            }
        }
    }

    static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MongoException mongo
                    && (mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                            || mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))) {
                return true;
            }
        }
        return false;
    }

    private static void backoff(int attempt) {
        long ceiling = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << attempt);
        try {
            Thread.sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(1, ceiling + 1)));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off a transaction retry", ex);
        }
    }
}
