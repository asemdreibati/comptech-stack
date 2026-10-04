package io.souqly.seller.application;

/** Reasons a request about an application is refused. */
public final class ApplicationExceptions {

    private ApplicationExceptions() {
    }

    /** Also used for other people's applications, so their existence is not revealed. */
    public static class ApplicationNotFoundException extends RuntimeException {
        public ApplicationNotFoundException(Object id) {
            super("Application " + id + " not found");
        }
    }

    /** A business rule refused the request; {@code code} is the stable problem code. */
    public static class ApplicationConflictException extends RuntimeException {

        private final String code;

        public ApplicationConflictException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** An uploaded document failed verification and was deleted. */
    public static class InvalidDocumentException extends RuntimeException {
        public InvalidDocumentException(String message) {
            super(message);
        }
    }
}
