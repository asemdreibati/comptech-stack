package io.souqly.search.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SearchResults(long total, int page, int size, List<Item> items, Facets facets) {

    public record Item(String sku, String productId, String category, Map<String, String> title, String brand,
            BigDecimal price, String currency, String imageUrl, boolean inStock) {
    }

    public record Facets(List<Count> categories, List<Count> brands, List<Attribute> attributes, PriceRange price) {
    }

    public record Count(String value, String label, long count, boolean selected) {
    }

    public record Attribute(String name, String label, List<Count> values) {
    }

    public record PriceRange(BigDecimal min, BigDecimal max) {
    }

    public record Suggestion(String sku, String text) {
    }
}
