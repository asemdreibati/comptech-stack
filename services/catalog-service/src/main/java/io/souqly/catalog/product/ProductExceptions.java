package io.souqly.catalog.product;

/** Failures a client can act on, each mapped to a stable problem code. */
public final class ProductExceptions {

    private ProductExceptions() {
    }

    public static class ProductNotFoundException extends RuntimeException {

        public ProductNotFoundException(String id) {
            super("Product " + id + " not found");
        }
    }

    public static class ProductAccessDeniedException extends RuntimeException {

        public enum Reason {
            NOT_PRODUCT_OWNER, SELLER_IDENTITY_REQUIRED
        }

        private final Reason reason;

        public ProductAccessDeniedException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    public static class SkuTakenException extends RuntimeException {

        public SkuTakenException(String sku) {
            super("SKU " + sku + " is already listed");
        }
    }

    /** The If-Match version is not the current one: someone else changed the listing first. */
    public static class VersionConflictException extends RuntimeException {

        private final long currentVersion;

        public VersionConflictException(long expected, long current) {
            super("Listing was modified: expected version " + expected + ", current version is " + current);
            this.currentVersion = current;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }

    /** A rule of the listing lifecycle was broken; {@code code} says which. */
    public static class ProductStateException extends RuntimeException {

        private final String code;

        public ProductStateException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The uploaded file failed verification and was deleted. */
    public static class InvalidImageException extends RuntimeException {

        public InvalidImageException(String message) {
            super(message);
        }
    }
}
