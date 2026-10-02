package io.souqly.inventory.stock;

import java.time.Instant;

import io.souqly.inventory.config.InventoryProperties;
import io.souqly.platform.outbox.OutboxWriter;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The only way stock levels change. Every change bumps the SKU's version and records a
 * {@link StockLevelEvent} in the outbox, so no code path can move stock without the rest of the
 * platform hearing about it. Must be called inside a MongoDB transaction.
 */
@Component
public class StockLedger {

    private final MongoTemplate mongo;
    private final OutboxWriter outbox;
    private final String topic;

    public StockLedger(MongoTemplate mongo, OutboxWriter outbox, InventoryProperties properties) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.topic = properties.topics().stockLevels();
    }

    /**
     * Applies deltas to a SKU. With {@code requireAvailable}, the change only happens if at least
     * that many units are available; this guard is what makes overselling impossible.
     *
     * @return the stock after the change, or {@code null} if the SKU is missing or the guard failed
     */
    public StockItem apply(String sku, long availableDelta, long reservedDelta, Long requireAvailable, Instant now) {
        Criteria criteria = where("_id").is(sku);
        if (requireAvailable != null) {
            criteria = criteria.and("available").gte(requireAvailable);
        }
        var update = new Update().inc("available", availableDelta).inc("reserved", reservedDelta);
        StockItem item = mongo.findAndModify(query(criteria), versioned(update, now),
                FindAndModifyOptions.options().returnNew(true), StockItem.class);
        if (item != null) {
            record(item, now);
        }
        return item;
    }

    /** Adds the version bump and timestamp every stock update carries. */
    static Update versioned(Update update, Instant now) {
        return update.inc("version", 1).set("updatedAt", now);
    }

    /** Publishes the level of a SKU changed by an update this ledger did not build itself. */
    void record(StockItem item, Instant now) {
        outbox.append(StockLevelEvent.messageFor(topic, item, now));
    }
}
