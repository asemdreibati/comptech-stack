package io.souqly.inventory.stock;

/** The caller may write stock in general, but not this SKU. */
public class StockAccessDeniedException extends RuntimeException {

    public enum Reason {
        /** The SKU belongs to another seller, or to the marketplace itself. */
        NOT_SKU_OWNER,
        /** The token grants seller permissions but carries no seller account to scope them to. */
        SELLER_IDENTITY_REQUIRED
    }

    private final Reason reason;

    public StockAccessDeniedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
