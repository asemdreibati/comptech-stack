package io.souqly.search.api;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.souqly.search.query.SearchRequest;
import io.souqly.search.query.SearchResults;
import io.souqly.search.query.SearchService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public product search. Attribute filters are passed as {@code f.<attribute>=<value>} and may
 * repeat, e.g. {@code ?q=phone&f.storage=128&f.storage=256&f.color=black}.
 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    static final int MAX_RESULT_WINDOW = 10_000;
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    /** Results may lag the catalog by a second anyway, so a short shared cache costs nothing. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    ResponseEntity<SearchResults> search(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) String category,
            @RequestParam(name = "brand", required = false) List<@Size(max = 60) String> brands,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam MultiValueMap<String, String> params) {
        if (category != null && !SLUG.matcher(category).matches()) {
            throw new InvalidSearchException("Unknown category format");
        }
        if ((long) (page + 1) * size > MAX_RESULT_WINDOW) {
            throw new InvalidSearchException("Results beyond the first " + MAX_RESULT_WINDOW
                    + " are not available; narrow the search instead");
        }
        var request = new SearchRequest(q, category, brands != null ? brands : List.of(), minPrice, maxPrice, inStock,
                attributeFilters(params), parseSort(sort), page, size);
        return ResponseEntity.ok().cacheControl(CACHE).body(search.search(request));
    }

    @GetMapping("/suggest")
    ResponseEntity<List<SearchResults.Suggestion>> suggest(
            @RequestParam @Size(min = 2, max = 50) String q,
            @RequestParam(defaultValue = "en") @jakarta.validation.constraints.Pattern(regexp = "en|ar") String lang,
            @RequestParam(defaultValue = "8") @Min(1) @Max(20) int size) {
        return ResponseEntity.ok().cacheControl(CACHE).body(search.suggest(q, lang, size));
    }

    private static Map<String, List<String>> attributeFilters(MultiValueMap<String, String> params) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        params.forEach((key, values) -> {
            if (!key.startsWith("f.")) {
                return;
            }
            String name = key.substring(2);
            if (!ATTRIBUTE_NAME.matcher(name).matches() || values.size() > 20
                    || values.stream().anyMatch(v -> v.isBlank() || v.length() > 60)) {
                throw new InvalidSearchException("Invalid filter " + key);
            }
            filters.put(name, List.copyOf(values));
        });
        if (filters.size() > 10) {
            throw new InvalidSearchException("At most 10 attribute filters");
        }
        return filters;
    }

    private static SearchRequest.Sort parseSort(String sort) {
        try {
            return SearchRequest.Sort.valueOf(sort.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidSearchException("sort must be one of relevance, price_asc, price_desc, newest");
        }
    }
}
