package io.souqly.platform.mongo;

/** Too many concurrent writers on the same stock; safe for the client to retry shortly. */
public class TransactionContentionException extends RuntimeException {

    public TransactionContentionException(int attempts, Throwable cause) {
        super(attempts > 0
                ? "Transaction aborted after " + attempts + " attempts due to write contention"
                : "Timed out waiting for a stock write slot", cause);
    }
}
