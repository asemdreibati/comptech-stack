package io.souqly.platform.formio;

import java.util.List;

/** A submission does not satisfy its form. */
public class FormValidationException extends RuntimeException {

    private final List<FieldError> errors;

    public FormValidationException(List<FieldError> errors) {
        super("The submission does not satisfy its form");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
