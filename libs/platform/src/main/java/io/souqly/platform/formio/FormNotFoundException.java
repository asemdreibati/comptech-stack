package io.souqly.platform.formio;

public class FormNotFoundException extends RuntimeException {

    public FormNotFoundException(String path) {
        super("No Form.io form at path " + path);
    }
}
