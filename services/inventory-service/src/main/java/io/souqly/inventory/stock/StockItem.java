package io.souqly.inventory.stock;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Stock for one SKU. {@code available} can be promised to new orders; {@code reserved} is
 * held by pending reservations. Both only ever change through conditional atomic updates.
 * {@code sellerId} is the seller that owns the SKU, or {@code null} for marketplace-owned stock;
 * it is fixed by whoever creates the SKU and never changes afterwards.
 */
@Document("stock")
public record StockItem(@Id String sku, String sellerId, long available, long reserved, Instant updatedAt) {
}
