package io.souqly.inventory.stock;

import java.time.Instant;
import java.util.UUID;

import io.souqly.platform.outbox.OutboxWriter;

/**
 * The absolute stock level of a SKU after a change, published to a compacted topic keyed by SKU.
 * {@code version} increases with every change, so consumers can discard events that arrive late
 * or twice: apply an event only if its version is newer than the one already applied.
 */
public record StockLevelEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String sku,
        String sellerId,
        long available,
        long reserved,
        long version) {

    public static final String TYPE = "StockLevelChanged";

    static OutboxWriter.Message messageFor(String topic, StockItem item, Instant occurredAt) {
        String eventId = UUID.randomUUID().toString();
        var event = new StockLevelEvent(eventId, TYPE, occurredAt, item.sku(), item.sellerId(), item.available(),
                item.reserved(), item.version());
        return new OutboxWriter.Message(eventId, topic, "Stock", item.sku(), TYPE, occurredAt, event);
    }
}
