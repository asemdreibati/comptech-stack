package io.souqly.order.checkout;

import java.util.List;

/** Reasons a checkout request is refused before any order exists. */
public final class CheckoutExceptions {

    private CheckoutExceptions() {
    }

    /** The buyer reused an {@code Idempotency-Key} for a different request. */
    public static class IdempotencyKeyReusedException extends RuntimeException {
        public IdempotencyKeyReusedException(String key) {
            super("Idempotency-Key " + key + " was already used for a different checkout");
        }
    }

    /** Some SKUs are unknown, not live, or cannot be priced. */
    public static class ProductUnavailableException extends RuntimeException {

        private final List<String> skus;

        public ProductUnavailableException(List<String> skus) {
            super("Not available for purchase: " + String.join(", ", skus));
            this.skus = skus;
        }

        public List<String> skus() {
            return skus;
        }
    }

    /** One order is paid in one currency. */
    public static class MixedCurrencyException extends RuntimeException {
        public MixedCurrencyException(List<String> currencies) {
            super("Items are priced in different currencies: " + String.join(", ", currencies));
        }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String id) {
            super("Order " + id + " not found");
        }
    }
}
