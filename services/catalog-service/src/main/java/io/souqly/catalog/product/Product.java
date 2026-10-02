package io.souqly.catalog.product;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.souqly.catalog.formio.Facet;
import io.souqly.catalog.i18n.LocalizedText;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A seller's listing. {@code sku} is unique and links the listing to its stock in the inventory
 * service. {@code attributes} have passed the category's Form.io form; {@code facets} are the
 * filterable subset, derived at write time. {@code version} increases with every change: it is
 * the listing's ETag, and it orders the events search receives.
 */
@Document("products")
public record Product(
        @Id String id,
        String sku,
        String sellerId,
        String category,
        LocalizedText title,
        LocalizedText description,
        String brand,
        Money price,
        Map<String, Object> attributes,
        List<Facet> facets,
        List<ProductImage> images,
        ProductStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasReadyImage() {
        return images != null && images.stream().anyMatch(i -> i.status() == ProductImage.Status.READY);
    }
}
