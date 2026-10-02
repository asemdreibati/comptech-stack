package io.souqly.search.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a {@link SearchRequest} into an OpenSearch query.
 *
 * <p>Text matching covers English and Arabic fields (each with its own analyzer) and tolerates
 * typos. Facets are <em>disjunctive</em>, the behaviour shoppers expect: after choosing "128 GB",
 * the storage facet still shows how many 256 GB phones exist. User-selected filters therefore
 * go into {@code post_filter}, and every facet is counted with all selected filters except its own.
 */
final class SearchQueryBuilder {

    static final String ATTRIBUTE_AGG_PREFIX = "attr_";
    private static final List<String> SOURCE_FIELDS = List.of(
            "sku", "productId", "category", "title", "brandLabel", "price", "currency", "imageUrl", "inStock");

    private SearchQueryBuilder() {
    }

    static Map<String, Object> search(SearchRequest request) {
        List<Object> base = baseFilters(request, true);
        Map<String, Object> text = textQuery(request.text());

        Map<String, Object> body = obj(
                "from", request.page() * request.size(),
                "size", request.size(),
                "track_total_hits", true,
                "_source", SOURCE_FIELDS,
                "query", bool(List.of(text), base),
                "post_filter", bool(List.of(), selectable(request, null, null)),
                "sort", sort(request.sort()));

        Map<String, Object> aggs = new LinkedHashMap<>();
        aggs.put("brands", obj(
                "filter", bool(List.of(), selectable(request, "brand", null)),
                "aggs", obj("values", terms("brand", 30, obj("label", terms("brandLabel", 1, null))))));
        aggs.put("attributes", obj(
                "filter", bool(List.of(), selectable(request, null, null)),
                "aggs", obj("facets", obj("nested", obj("path", "facets"),
                        "aggs", obj("names", terms("facets.name", 20, attributeValueAggs()))))));
        int i = 0;
        for (String name : request.attributes().keySet()) {
            aggs.put(ATTRIBUTE_AGG_PREFIX + i++, obj(
                    "filter", bool(List.of(), selectable(request, null, name)),
                    "aggs", obj("facets", obj("nested", obj("path", "facets"),
                            "aggs", obj("only", obj("filter", obj("term", obj("facets.name", name)),
                                    "aggs", attributeValueAggs()))))));
        }
        aggs.put("price", obj(
                "filter", bool(List.of(), selectable(request, null, null)),
                "aggs", obj("min", obj("min", obj("field", "price")), "max", obj("max", obj("field", "price")))));
        // Categories are part of the main query, so count them on a query without the category filter.
        List<Object> scoped = new ArrayList<>(baseFilters(request, false));
        scoped.addAll(selectable(request, null, null));
        aggs.put("categories", obj(
                "global", obj(),
                "aggs", obj("scoped", obj(
                        "filter", bool(List.of(text), scoped),
                        "aggs", obj("values", terms("category", 30, null))))));
        body.put("aggs", aggs);
        return body;
    }

    static Map<String, Object> suggest(String prefix, int size) {
        return obj(
                "size", size,
                "_source", List.of("sku", "title"),
                "query", bool(
                        List.of(obj("multi_match", obj(
                                "query", prefix,
                                "fields", List.of("title.en.autocomplete", "title.ar.autocomplete"),
                                "operator", "and"))),
                        List.of(obj("term", obj("visible", true)))));
    }

    private static Map<String, Object> textQuery(String text) {
        if (text == null || text.isBlank()) {
            return obj("match_all", obj());
        }
        return obj("multi_match", obj(
                "query", text,
                "type", "best_fields",
                "fields", List.of("title.en^3", "title.ar^3", "brand.text^2", "description.en", "description.ar"),
                // One typo for words of 3-5 letters, two from 6; the first letter must match.
                "fuzziness", "AUTO",
                "prefix_length", 1,
                "operator", "and"));
    }

    /** Filters that narrow the result set but are not offered as facets. */
    private static List<Object> baseFilters(SearchRequest request, boolean includeCategory) {
        List<Object> filters = new ArrayList<>();
        filters.add(obj("term", obj("visible", true)));
        if (includeCategory && request.category() != null) {
            filters.add(obj("term", obj("category", request.category())));
        }
        if (request.minPrice() != null || request.maxPrice() != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (request.minPrice() != null) {
                range.put("gte", request.minPrice());
            }
            if (request.maxPrice() != null) {
                range.put("lte", request.maxPrice());
            }
            filters.add(obj("range", obj("price", range)));
        }
        if (request.inStockOnly()) {
            filters.add(obj("term", obj("inStock", true)));
        }
        return filters;
    }

    /**
     * Facet filters the shopper selected, optionally leaving one out so that facet can be counted
     * as if it were not selected.
     */
    private static List<Object> selectable(SearchRequest request, String skipFacet, String skipAttribute) {
        List<Object> filters = new ArrayList<>();
        if (!"brand".equals(skipFacet) && !request.brands().isEmpty()) {
            filters.add(obj("terms", obj("brand", request.brands())));
        }
        request.attributes().forEach((name, values) -> {
            if (!name.equals(skipAttribute)) {
                filters.add(obj("nested", obj("path", "facets", "query", bool(List.of(), List.of(
                        obj("term", obj("facets.name", name)),
                        obj("terms", obj("facets.value", values)))))));
            }
        });
        return filters;
    }

    private static Map<String, Object> attributeValueAggs() {
        return obj(
                "label", terms("facets.label", 1, null),
                "values", terms("facets.value", 30, obj("label", terms("facets.valueLabel", 1, null))));
    }

    private static List<Object> sort(SearchRequest.Sort sort) {
        return switch (sort) {
            case RELEVANCE -> List.of("_score", obj("listedAt", "desc"));
            case PRICE_ASC -> List.of(obj("price", "asc"), "_score");
            case PRICE_DESC -> List.of(obj("price", "desc"), "_score");
            case NEWEST -> List.of(obj("listedAt", "desc"));
        };
    }

    private static Map<String, Object> bool(List<Object> must, List<Object> filter) {
        Map<String, Object> bool = new LinkedHashMap<>();
        if (!must.isEmpty()) {
            bool.put("must", must);
        }
        bool.put("filter", filter);
        return obj("bool", bool);
    }

    private static Map<String, Object> terms(String field, int size, Map<String, Object> subAggs) {
        Map<String, Object> agg = obj("terms", obj("field", field, "size", size));
        if (subAggs != null) {
            agg.put("aggs", subAggs);
        }
        return agg;
    }

    /** A JSON object from alternating keys and values, keeping order. */
    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
