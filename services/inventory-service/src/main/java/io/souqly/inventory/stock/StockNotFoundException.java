package io.souqly.inventory.stock;

public class StockNotFoundException extends RuntimeException {

    private final String sku;

    public StockNotFoundException(String sku) {
        super("No stock record for SKU " + sku);
        this.sku = sku;
    }

    public String sku() {
        return sku;
    }
}
