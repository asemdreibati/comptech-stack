package io.souqly.platform.formio;

/** @param field the field's key (dotted for nested fields), or empty for the whole submission */
public record FieldError(String field, String message) {
}
