package io.souqly.search.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.souqly.search.config.SearchProperties;
import io.souqly.search.index.OpenSearchClient;
import io.souqly.search.query.SearchResults.Attribute;
import io.souqly.search.query.SearchResults.Count;
import io.souqly.search.query.SearchResults.Facets;
import io.souqly.search.query.SearchResults.Item;
import io.souqly.search.query.SearchResults.PriceRange;
import io.souqly.search.query.SearchResults.Suggestion;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private final OpenSearchClient opensearch;
    private final String alias;

    public SearchService(OpenSearchClient opensearch, SearchProperties properties) {
        this.opensearch = opensearch;
        this.alias = properties.opensearch().alias();
    }

    public SearchResults search(SearchRequest request) {
        JsonNode response = execute(SearchQueryBuilder.search(request));
        List<Item> items = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode doc = hit.path("_source");
            Map<String, String> title = new LinkedHashMap<>();
            doc.path("title").properties().forEach(e -> title.put(e.getKey(), e.getValue().asString()));
            items.add(new Item(doc.path("sku").asString(), doc.path("productId").asString(),
                    doc.path("category").asString(), title, doc.path("brandLabel").asString(null),
                    doc.path("price").decimalValue(), doc.path("currency").asString(null),
                    doc.path("imageUrl").asString(null), doc.path("inStock").asBoolean(false)));
        }
        JsonNode aggs = response.path("aggregations");
        var facets = new Facets(
                counts(aggs.path("categories").path("scoped").path("values"), request.category() == null
                        ? List.of() : List.of(request.category())),
                counts(aggs.path("brands").path("values"), request.brands()),
                attributes(aggs, request),
                priceRange(aggs.path("price")));
        return new SearchResults(response.path("hits").path("total").path("value").asLong(), request.page(),
                request.size(), items, facets);
    }

    public List<Suggestion> suggest(String prefix, String language, int size) {
        JsonNode response = execute(SearchQueryBuilder.suggest(prefix, size));
        List<Suggestion> suggestions = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode title = hit.path("_source").path("title");
            String text = title.path(language).asString(null);
            suggestions.add(new Suggestion(hit.path("_source").path("sku").asString(),
                    text != null ? text : title.path("en").asString()));
        }
        return suggestions;
    }

    /**
     * Attributes nobody has selected come from the aggregation over all filters; each selected
     * attribute comes from its own aggregation that ignores its selection (disjunctive facets).
     */
    private static List<Attribute> attributes(JsonNode aggs, SearchRequest request) {
        Map<String, Attribute> byName = new LinkedHashMap<>();
        for (JsonNode bucket : aggs.path("attributes").path("facets").path("names").path("buckets")) {
            String name = bucket.path("key").asString();
            byName.put(name, attribute(name, bucket, List.of()));
        }
        int i = 0;
        for (var selected : request.attributes().entrySet()) {
            JsonNode only = aggs.path(SearchQueryBuilder.ATTRIBUTE_AGG_PREFIX + i++).path("facets").path("only");
            byName.put(selected.getKey(), attribute(selected.getKey(), only, selected.getValue()));
        }
        return List.copyOf(byName.values());
    }

    private static Attribute attribute(String name, JsonNode agg, List<String> selected) {
        String label = agg.path("label").path("buckets").path(0).path("key").asString(name);
        return new Attribute(name, label, counts(agg.path("values"), selected));
    }

    private static List<Count> counts(JsonNode termsAgg, List<String> selected) {
        List<Count> counts = new ArrayList<>();
        for (JsonNode bucket : termsAgg.path("buckets")) {
            String value = bucket.path("key").asString();
            String label = bucket.path("label").path("buckets").path(0).path("key").asString(value);
            counts.add(new Count(value, label, bucket.path("doc_count").asLong(),
                    selected.stream().anyMatch(s -> s.equalsIgnoreCase(value))));
        }
        return counts;
    }

    private static PriceRange priceRange(JsonNode agg) {
        JsonNode min = agg.path("min").path("value");
        JsonNode max = agg.path("max").path("value");
        return new PriceRange(min.isNumber() ? min.decimalValue() : null, max.isNumber() ? max.decimalValue() : null);
    }

    private JsonNode execute(Map<String, Object> query) {
        OpenSearchClient.Response response;
        try {
            response = opensearch.send(HttpMethod.POST, "/" + alias + "/_search", query);
        }
        catch (RuntimeException ex) {
            throw new SearchUnavailableException("OpenSearch is unreachable", ex);
        }
        if (!response.ok()) {
            throw new SearchUnavailableException("Search failed with status " + response.status() + ": "
                    + response.body().path("error").path("reason").asString(""), null);
        }
        return response.body();
    }
}
