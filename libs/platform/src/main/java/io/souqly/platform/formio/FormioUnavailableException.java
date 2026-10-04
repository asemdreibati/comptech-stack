package io.souqly.platform.formio;

/** Form.io could not be reached, so submissions cannot be validated and must be refused. */
public class FormioUnavailableException extends RuntimeException {

    public FormioUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
