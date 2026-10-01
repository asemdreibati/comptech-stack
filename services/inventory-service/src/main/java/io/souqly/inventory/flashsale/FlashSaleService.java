package io.souqly.inventory.flashsale;

import java.util.Optional;

import io.souqly.inventory.stock.InsufficientStockException;
import io.souqly.inventory.stock.StockService;

import org.springframework.stereotype.Service;

@Service
public class FlashSaleService {

    private final FlashSaleGate gate;
    private final StockService stock;

    public FlashSaleService(FlashSaleGate gate, StockService stock) {
        this.gate = gate;
        this.stock = stock;
    }

    /**
     * Arms the gate for a SKU. Without an explicit allocation the whole available stock is
     * put on sale; an allocation can never exceed what is available right now.
     */
    public long arm(String sku, Long allocation) {
        long available = stock.get(sku).available();
        long tokens = allocation != null ? allocation : available;
        if (tokens > available) {
            throw new InsufficientStockException(sku, tokens, available);
        }
        gate.arm(sku, tokens);
        return tokens;
    }

    public void disarm(String sku) {
        gate.disarm(sku);
    }

    public Optional<Long> remainingTokens(String sku) {
        return gate.remainingTokens(sku);
    }
}
