package io.souqly.inventory.stock;

import java.time.Clock;

import io.souqly.inventory.flashsale.FlashSaleGate;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Service
public class StockService {

    private final MongoTemplate mongo;
    private final FlashSaleGate gate;
    private final Clock clock;

    public StockService(MongoTemplate mongo, FlashSaleGate gate, Clock clock) {
        this.mongo = mongo;
        this.gate = gate;
        this.clock = clock;
    }

    public StockItem get(String sku) {
        var item = mongo.findById(sku, StockItem.class);
        if (item == null) {
            throw new StockNotFoundException(sku);
        }
        return item;
    }

    /** Adds sellable units, creating the SKU on first restock. */
    public StockItem restock(String sku, long quantity) {
        var update = new Update()
                .inc("available", quantity)
                .setOnInsert("reserved", 0L)
                .set("updatedAt", clock.instant());
        var item = mongo.findAndModify(query(where("_id").is(sku)), update,
                FindAndModifyOptions.options().upsert(true).returnNew(true), StockItem.class);
        // Units added during a flash sale become claimable immediately.
        gate.addTokensIfArmed(sku, quantity);
        return item;
    }
}
