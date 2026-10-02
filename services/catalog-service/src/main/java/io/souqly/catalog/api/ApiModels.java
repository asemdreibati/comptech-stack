package io.souqly.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.souqly.catalog.category.Category;
import io.souqly.catalog.formio.Facet;
import io.souqly.catalog.i18n.LocalizedText;
import io.souqly.catalog.product.Money;
import io.souqly.catalog.product.Product;
import io.souqly.catalog.product.ProductImage;
import io.souqly.catalog.product.ProductService.ListingDetails;
import io.souqly.catalog.product.ProductStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

final class ApiModels {

    static final String SKU = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    static final String SLUG = "^[a-z0-9][a-z0-9-]{0,39}$";

    private ApiModels() {
    }

    record Name(@NotBlank @Size(max = 80) String en, @Size(max = 80) String ar) {
        LocalizedText toText() {
            return new LocalizedText(en, ar);
        }
    }

    record Title(@NotBlank @Size(max = 200) String en, @Size(max = 200) String ar) {
        LocalizedText toText() {
            return new LocalizedText(en, ar);
        }
    }

    record Description(@NotBlank @Size(max = 5000) String en, @Size(max = 5000) String ar) {
        LocalizedText toText() {
            return new LocalizedText(en, ar);
        }
    }

    record Price(
            @NotNull @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "AED|SAR|EGP") String currency) {
        Money toMoney() {
            return new Money(amount, currency);
        }
    }

    record CategoryRequest(@NotNull @Valid Name name, @NotNull @Pattern(regexp = "^[a-z0-9-]{1,60}$") String formPath) {
    }

    record CategoryResponse(String slug, LocalizedText name, String formPath) {
        static CategoryResponse from(Category category) {
            return new CategoryResponse(category.slug(), category.name(), category.formPath());
        }
    }

    record ListingRequest(
            @NotNull @Pattern(regexp = SLUG) String category,
            @NotNull @Valid Title title,
            @NotNull @Valid Description description,
            @NotBlank @Size(max = 60) String brand,
            @NotNull @Valid Price price,
            @NotNull Map<String, Object> attributes) {
        ListingDetails toDetails() {
            return new ListingDetails(category, title.toText(), description.toText(), brand, price.toMoney(), attributes);
        }
    }

    record CreateProductRequest(
            @NotNull @Pattern(regexp = SKU) String sku,
            @NotNull @Pattern(regexp = SLUG) String category,
            @NotNull @Valid Title title,
            @NotNull @Valid Description description,
            @NotBlank @Size(max = 60) String brand,
            @NotNull @Valid Price price,
            @NotNull Map<String, Object> attributes) {
        ListingDetails toDetails() {
            return new ListingDetails(category, title.toText(), description.toText(), brand, price.toMoney(), attributes);
        }
    }

    record ImageView(String imageId, ProductImage.Status status, String contentType, long sizeBytes, String url) {
        static ImageView from(ProductImage image) {
            return new ImageView(image.imageId(), image.status(), image.contentType(), image.sizeBytes(), image.url());
        }
    }

    record ProductResponse(
            String id, String sku, String sellerId, String category, LocalizedText title, LocalizedText description,
            String brand, Money price, Map<String, Object> attributes, List<Facet> facets, List<ImageView> images,
            ProductStatus status, long version, Instant createdAt, Instant updatedAt) {
        static ProductResponse from(Product p) {
            return new ProductResponse(p.id(), p.sku(), p.sellerId(), p.category(), p.title(), p.description(),
                    p.brand(), p.price(), p.attributes(), p.facets(), p.images().stream().map(ImageView::from).toList(),
                    p.status(), p.version(), p.createdAt(), p.updatedAt());
        }
    }

    record ImageUploadRequest(@NotBlank String contentType, @Positive long sizeBytes) {
    }

    record ImageUploadResponse(String imageId, String uploadUrl, String method, Map<String, String> headers,
            Instant expiresAt) {
    }
}
