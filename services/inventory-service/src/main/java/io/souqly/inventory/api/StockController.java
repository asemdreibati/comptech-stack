package io.souqly.inventory.api;

import io.souqly.inventory.api.ApiModels.RestockRequest;
import io.souqly.inventory.api.ApiModels.StockResponse;
import io.souqly.platform.security.Caller;
import io.souqly.inventory.stock.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
class StockController {

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/{sku}")
    StockResponse get(@PathVariable @Pattern(regexp = ApiModels.IDENTIFIER) String sku) {
        return StockResponse.from(stock.get(sku));
    }

    @PostMapping("/{sku}/restock")
    StockResponse restock(@PathVariable @Pattern(regexp = ApiModels.IDENTIFIER) String sku,
            @Valid @RequestBody RestockRequest request, Authentication authentication) {
        return StockResponse.from(stock.restock(sku, request.quantity(), Caller.from(authentication)));
    }
}
