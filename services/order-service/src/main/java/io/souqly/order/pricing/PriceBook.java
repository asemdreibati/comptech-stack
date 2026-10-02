package io.souqly.order.pricing;

import java.util.Collection;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Local replica of listing prices. Checkout reads prices here rather than calling the catalog, so
 * an order can be placed while the catalog is down, and the price charged is the one the buyer
 * saw a moment ago, not one fetched at an unknown later point.
 */
@Component
public class PriceBook {

    private final MongoTemplate mongo;

    public PriceBook(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public List<ProductPrice> find(Collection<String> skus) {
        return mongo.find(query(where("_id").in(skus)), ProductPrice.class);
    }

    /**
     * Stores a listing's state if it is newer than what is held. The version condition is part of
     * the upsert's filter: for an older event the filter misses, the upsert tries to insert a
     * duplicate {@code _id}, and the unique key turns that into a no-op.
     *
     * @return whether the event was applied
     */
    public boolean apply(ProductPrice price) {
        Criteria newer = where("_id").is(price.sku())
                .orOperator(where("version").lt(price.version()), where("version").exists(false));
        var update = new Update()
                .set("productId", price.productId())
                .set("sellerId", price.sellerId())
                .set("title", price.title())
                .set("amount", price.amount())
                .set("currency", price.currency())
                .set("purchasable", price.purchasable())
                .set("version", price.version());
        try {
            mongo.upsert(query(newer), update, ProductPrice.class);
            return true;
        }
        catch (DuplicateKeyException stale) {
            return false;
        }
    }
}
