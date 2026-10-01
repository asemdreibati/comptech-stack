package io.souqly.inventory.stock;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stock for one SKU. {@code available} can be promised to new orders; {@code reserved} is
 * held by pending reservations. Both only ever change through conditional atomic updates.
 */
@Document("stock")
public record StockItem(@Id String sku, long available, long reserved, Instant updatedAt) {
}
