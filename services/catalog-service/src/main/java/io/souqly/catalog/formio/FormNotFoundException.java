package io.souqly.catalog.formio;

public class FormNotFoundException extends RuntimeException {

    public FormNotFoundException(String path) {
        super("No Form.io form at path " + path);
    }
}
