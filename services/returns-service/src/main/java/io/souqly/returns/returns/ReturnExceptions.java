package io.souqly.returns.returns;

public final class ReturnExceptions {

    private ReturnExceptions() {
    }

    /** Also used for other people's returns, so their existence is not revealed. */
    public static class ReturnNotFoundException extends RuntimeException {
        public ReturnNotFoundException(Object id) {
            super("Return " + id + " not found");
        }
    }

    /** A business rule refused the request; {@code code} is the stable problem code. */
    public static class ReturnRejectedException extends RuntimeException {

        private final String code;

        public ReturnRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
