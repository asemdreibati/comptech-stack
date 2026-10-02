package io.souqly.inventory.stock;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import io.souqly.inventory.flashsale.FlashSaleGate;
import io.souqly.inventory.security.Permissions;
import io.souqly.inventory.stock.StockAccessDeniedException.Reason;
import io.souqly.inventory.support.SkuLocks;
import io.souqly.platform.mongo.MongoTransactions;
import io.souqly.platform.security.Caller;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

@Service
public class StockService {

    private final MongoTemplate mongo;
    private final MongoTransactions transactions;
    private final SkuLocks locks;
    private final StockLedger ledger;
    private final FlashSaleGate gate;
    private final Clock clock;

    public StockService(MongoTemplate mongo, MongoTransactions transactions, SkuLocks locks, StockLedger ledger,
            FlashSaleGate gate, Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.locks = locks;
        this.ledger = ledger;
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

    /**
     * Adds sellable units, creating the SKU on first restock. A seller can only restock SKUs it
     * owns; restocking a new SKU makes the caller's seller account its owner.
     *
     * <p>Ownership is enforced inside the single atomic upsert rather than by reading first: the
     * filter includes the caller's seller ID, so a SKU owned by someone else does not match, the
     * upsert tries to insert a second document with the same {@code _id}, and the unique key
     * rejects it. There is no window between "check owner" and "write".
     */
    public StockItem restock(String sku, long quantity, Caller caller) {
        Criteria filter = where("_id").is(sku);
        if (!caller.has(Permissions.STOCK_WRITE_ANY)) {
            if (caller.sellerId() == null) {
                throw new StockAccessDeniedException(Reason.SELLER_IDENTITY_REQUIRED,
                        "Token has seller permissions but no seller_id claim");
            }
            filter = filter.and("sellerId").is(caller.sellerId());
        }
        var query = query(filter);
        StockItem item;
        try {
            // Same lock and transaction as reservations, so a restock never aborts a checkout batch.
            item = locks.withLocks(List.of(sku), () -> transactions.execute(() -> {
                Instant now = clock.instant();
                var update = StockLedger.versioned(new Update().inc("available", quantity).setOnInsert("reserved", 0L),
                        now);
                var updated = mongo.findAndModify(query, update,
                        FindAndModifyOptions.options().upsert(true).returnNew(true), StockItem.class);
                ledger.record(updated, now);
                return updated;
            }));
        }
        catch (DuplicateKeyException ex) {
            throw new StockAccessDeniedException(Reason.NOT_SKU_OWNER,
                    "SKU " + sku + " belongs to another seller");
        }
        // Units added during a flash sale become claimable immediately.
        gate.addTokensIfArmed(sku, quantity);
        return item;
    }
}
