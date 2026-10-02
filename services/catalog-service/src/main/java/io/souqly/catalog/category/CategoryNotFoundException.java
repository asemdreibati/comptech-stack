package io.souqly.catalog.category;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException(String slug) {
        super("Unknown category " + slug);
    }
}
