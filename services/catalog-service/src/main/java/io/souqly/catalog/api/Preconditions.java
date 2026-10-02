package io.souqly.catalog.api;

/** HTTP conditional-request handling for listing updates (RFC 9110, section 13). */
final class Preconditions {

    private Preconditions() {
    }

    /** The client must say which version it edited; an unconditional update would risk lost updates. */
    static class PreconditionRequiredException extends RuntimeException {

        PreconditionRequiredException(String message) {
            super(message);
        }
    }

    static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the ETag of the version you edited");
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException ex) {
            throw new PreconditionRequiredException("If-Match must be an ETag returned by this API");
        }
    }
}
