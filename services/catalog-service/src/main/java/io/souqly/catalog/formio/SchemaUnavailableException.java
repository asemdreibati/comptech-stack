package io.souqly.catalog.formio;

/** Form.io could not be reached; listings cannot be validated, so writes are refused. */
public class SchemaUnavailableException extends RuntimeException {

    public SchemaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
