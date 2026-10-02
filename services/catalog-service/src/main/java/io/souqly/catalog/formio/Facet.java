package io.souqly.catalog.formio;

/**
 * A filterable attribute value, e.g. Storage = "128" shown as "128 GB". Search builds its filters
 * from these without knowing any category's schema.
 */
public record Facet(String name, String label, String value, String valueLabel) {
}
