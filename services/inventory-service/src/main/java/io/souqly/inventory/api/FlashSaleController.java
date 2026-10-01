package io.souqly.inventory.api;

import io.souqly.inventory.api.ApiModels.ArmFlashSaleRequest;
import io.souqly.inventory.api.ApiModels.FlashSaleResponse;
import io.souqly.inventory.flashsale.FlashSaleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flash-sales")
class FlashSaleController {

    private final FlashSaleService flashSales;

    FlashSaleController(FlashSaleService flashSales) {
        this.flashSales = flashSales;
    }

    /** Arms the admission gate. Omit {@code tokens} to put all available stock on sale. */
    @PutMapping("/{sku}")
    FlashSaleResponse arm(@PathVariable @Pattern(regexp = ApiModels.IDENTIFIER) String sku,
            @Valid @RequestBody(required = false) ArmFlashSaleRequest request) {
        long tokens = flashSales.arm(sku, request != null ? request.tokens() : null);
        return new FlashSaleResponse(sku, tokens);
    }

    @GetMapping("/{sku}")
    ResponseEntity<FlashSaleResponse> get(@PathVariable @Pattern(regexp = ApiModels.IDENTIFIER) String sku) {
        return ResponseEntity.of(flashSales.remainingTokens(sku).map(tokens -> new FlashSaleResponse(sku, tokens)));
    }

    @DeleteMapping("/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disarm(@PathVariable @Pattern(regexp = ApiModels.IDENTIFIER) String sku) {
        flashSales.disarm(sku);
    }
}
