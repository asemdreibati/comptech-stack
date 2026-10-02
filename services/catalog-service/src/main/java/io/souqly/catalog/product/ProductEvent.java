package io.souqly.catalog.product;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.souqly.catalog.formio.Facet;
import io.souqly.catalog.i18n.LocalizedText;
import io.souqly.platform.outbox.OutboxWriter;

/**
 * The full state of a listing after a change (event-carried state transfer), published to a
 * compacted topic keyed by product ID. A consumer can rebuild its view from the topic alone and
 * needs no calls back to the catalog. Apply only if {@code version} is newer than what you hold.
 */
public record ProductEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String productId,
        String sku,
        String sellerId,
        String category,
        LocalizedText title,
        LocalizedText description,
        String brand,
        Money price,
        List<Facet> facets,
        List<String> imageUrls,
        ProductStatus status,
        long version,
        Instant createdAt) {

    public static final String TYPE = "ProductChanged";

    static OutboxWriter.Message messageFor(String topic, Product product, Instant occurredAt) {
        String eventId = UUID.randomUUID().toString();
        List<String> imageUrls = product.images().stream()
                .filter(image -> image.status() == ProductImage.Status.READY)
                .map(ProductImage::url)
                .toList();
        var event = new ProductEvent(eventId, TYPE, occurredAt, product.id(), product.sku(), product.sellerId(),
                product.category(), product.title(), product.description(), product.brand(), product.price(),
                product.facets(), imageUrls, product.status(), product.version(), product.createdAt());
        return new OutboxWriter.Message(eventId, topic, "Product", product.id(), TYPE, occurredAt, event);
    }
}
