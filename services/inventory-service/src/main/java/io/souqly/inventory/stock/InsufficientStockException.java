package io.souqly.inventory.stock;

public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final long requested;
    private final long available;

    public InsufficientStockException(String sku, long requested, long available) {
        super("Insufficient stock for SKU %s: requested %d, available %d".formatted(sku, requested, available));
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String sku() {
        return sku;
    }

    public long requested() {
        return requested;
    }

    public long available() {
        return available;
    }
}
