package io.souqly.catalog.product;

import java.math.BigDecimal;

/** An exact amount (stored as Decimal128) in an ISO 4217 currency. */
public record Money(BigDecimal amount, String currency) {
}
