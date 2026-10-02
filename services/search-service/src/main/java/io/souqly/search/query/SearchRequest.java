package io.souqly.search.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * @param attributes selected facet values by attribute name; values of one attribute are OR-ed,
 *                   different attributes are AND-ed (storage 128 or 256, and color black)
 */
public record SearchRequest(
        String text,
        String category,
        List<String> brands,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean inStockOnly,
        Map<String, List<String>> attributes,
        Sort sort,
        int page,
        int size) {

    public enum Sort {
        RELEVANCE, PRICE_ASC, PRICE_DESC, NEWEST
    }
}
