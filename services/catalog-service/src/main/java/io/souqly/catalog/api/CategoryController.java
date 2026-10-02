package io.souqly.catalog.api;

import java.time.Duration;
import java.util.List;

import io.souqly.catalog.api.ApiModels.CategoryRequest;
import io.souqly.catalog.api.ApiModels.CategoryResponse;
import io.souqly.catalog.category.CategoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    List<CategoryResponse> list() {
        return categories.list().stream().map(CategoryResponse::from).toList();
    }

    @GetMapping("/{slug}")
    CategoryResponse get(@PathVariable @Pattern(regexp = ApiModels.SLUG) String slug) {
        return CategoryResponse.from(categories.get(slug));
    }

    /** The category's Form.io form, for the seller portal to render with the Form.io renderer. */
    @GetMapping("/{slug}/form")
    ResponseEntity<JsonNode> form(@PathVariable @Pattern(regexp = ApiModels.SLUG) String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(categories.form(slug).raw());
    }

    @PutMapping("/{slug}")
    CategoryResponse save(@PathVariable @Pattern(regexp = ApiModels.SLUG) String slug,
            @Valid @RequestBody CategoryRequest request) {
        return CategoryResponse.from(categories.save(slug, request.name().toText(), request.formPath()));
    }
}
