package io.souqly.catalog.formio;

import java.util.List;

/** Listing attributes do not satisfy the category's form. */
public class AttributeValidationException extends RuntimeException {

    private final List<FieldError> errors;

    public AttributeValidationException(List<FieldError> errors) {
        super("Listing attributes are invalid for this category");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
