package io.souqly.catalog.product;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.souqly.catalog.category.CategoryNotFoundException;
import io.souqly.catalog.category.CategoryService;
import io.souqly.catalog.config.CatalogProperties;
import io.souqly.catalog.formio.AttributeValidator;
import io.souqly.catalog.formio.AttributeValidator.ValidatedAttributes;
import io.souqly.catalog.i18n.LocalizedText;
import io.souqly.catalog.product.ProductExceptions.ProductAccessDeniedException;
import io.souqly.catalog.product.ProductExceptions.ProductAccessDeniedException.Reason;
import io.souqly.catalog.product.ProductExceptions.ProductNotFoundException;
import io.souqly.catalog.product.ProductExceptions.ProductStateException;
import io.souqly.catalog.product.ProductExceptions.SkuTakenException;
import io.souqly.catalog.product.ProductExceptions.VersionConflictException;
import io.souqly.catalog.security.Permissions;
import io.souqly.platform.mongo.MongoTransactions;
import io.souqly.platform.outbox.OutboxWriter;
import io.souqly.platform.security.Caller;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Seller listings. Every change is a conditional update on the listing's version and writes a
 * {@link ProductEvent} to the outbox in the same transaction.
 */
@Service
public class ProductService {

    private final MongoTemplate mongo;
    private final MongoTransactions transactions;
    private final OutboxWriter outbox;
    private final CategoryService categories;
    private final AttributeValidator attributes;
    private final String topic;
    private final Clock clock;

    public ProductService(MongoTemplate mongo, MongoTransactions transactions, OutboxWriter outbox,
            CategoryService categories, AttributeValidator attributes, CatalogProperties properties, Clock clock) {
        this.mongo = mongo;
        this.transactions = transactions;
        this.outbox = outbox;
        this.categories = categories;
        this.attributes = attributes;
        this.topic = properties.topics().products();
        this.clock = clock;
    }

    /** What a seller submits; the same shape for creating and updating a listing. */
    public record ListingDetails(String category, LocalizedText title, LocalizedText description, String brand,
            Money price, Map<String, Object> attributes) {
    }

    public Product create(String sku, ListingDetails details, Caller caller) {
        String sellerId = caller.sellerId();
        if (sellerId == null && !caller.has(Permissions.PRODUCT_WRITE_ANY)) {
            throw new ProductAccessDeniedException(Reason.SELLER_IDENTITY_REQUIRED,
                    "Token has seller permissions but no seller_id claim");
        }
        ValidatedAttributes validated = validate(details);
        Instant now = clock.instant();
        var product = new Product(UUID.randomUUID().toString(), sku, sellerId, details.category(), details.title(),
                details.description(), details.brand(), details.price(), validated.attributes(), validated.facets(),
                List.of(), ProductStatus.DRAFT, 1, now, now);
        try {
            return transactions.execute(() -> {
                mongo.insert(product);
                outbox.append(ProductEvent.messageFor(topic, product, now));
                return product;
            });
        }
        catch (DuplicateKeyException ex) {
            throw new SkuTakenException(sku);
        }
    }

    /** Replaces the listing's details if it is still at {@code expectedVersion}. */
    public Product update(String id, long expectedVersion, ListingDetails details, Caller caller) {
        requireWriteAccess(load(id), caller);
        ValidatedAttributes validated = validate(details);
        return change(id, expectedVersion, where("_id").is(id), new Update()
                .set("category", details.category())
                .set("title", details.title())
                .set("description", details.description())
                .set("brand", details.brand())
                .set("price", details.price())
                .set("attributes", validated.attributes())
                .set("facets", validated.facets()));
    }

    /** Makes the listing visible to buyers. A listing needs at least one verified image to go live. */
    public Product publish(String id, Caller caller) {
        Product current = load(id);
        requireWriteAccess(current, caller);
        if (current.status() == ProductStatus.ACTIVE) {
            return current;
        }
        if (!current.hasReadyImage()) {
            throw new ProductStateException("IMAGE_REQUIRED", "A listing needs at least one image before it goes live");
        }
        return change(id, current.version(), where("_id").is(id),
                new Update().set("status", ProductStatus.ACTIVE));
    }

    public Product archive(String id, Caller caller) {
        Product current = load(id);
        requireWriteAccess(current, caller);
        if (current.status() == ProductStatus.ARCHIVED) {
            return current;
        }
        return change(id, current.version(), where("_id").is(id),
                new Update().set("status", ProductStatus.ARCHIVED));
    }

    /** Live listings are public; drafts and archived listings exist only for their owner and operations. */
    public Product get(String id, Caller caller) {
        Product product = load(id);
        if (product.status() != ProductStatus.ACTIVE && !canWrite(product, caller)) {
            throw new ProductNotFoundException(id);
        }
        return product;
    }

    Product load(String id) {
        Product product = mongo.findById(id, Product.class);
        if (product == null) {
            throw new ProductNotFoundException(id);
        }
        return product;
    }

    /**
     * Applies an update guarded by the expected version, bumps the version and publishes the new
     * state, all in one transaction.
     */
    Product change(String id, long expectedVersion, Criteria criteria, Update update) {
        Product updated = apply(criteria.and("version").is(expectedVersion), update);
        if (updated == null) {
            throw new VersionConflictException(expectedVersion, load(id).version());
        }
        return updated;
    }

    /**
     * Applies an update to the listing matching {@code criteria}, bumps its version and publishes
     * the new state, in one transaction.
     *
     * @return the updated listing, or {@code null} if nothing matched
     */
    Product apply(Criteria criteria, Update update) {
        // Built once, outside the transaction, because the transaction may be retried.
        var match = query(criteria);
        update.inc("version", 1);
        return transactions.execute(() -> {
            Instant now = clock.instant();
            Product updated = mongo.findAndModify(match, update.set("updatedAt", now),
                    FindAndModifyOptions.options().returnNew(true), Product.class);
            if (updated != null) {
                outbox.append(ProductEvent.messageFor(topic, updated, now));
            }
            return updated;
        });
    }

    static void requireWriteAccess(Product product, Caller caller) {
        if (caller.has(Permissions.PRODUCT_WRITE_ANY)) {
            return;
        }
        if (caller.sellerId() == null) {
            throw new ProductAccessDeniedException(Reason.SELLER_IDENTITY_REQUIRED,
                    "Token has seller permissions but no seller_id claim");
        }
        if (!caller.sellerId().equals(product.sellerId())) {
            throw new ProductAccessDeniedException(Reason.NOT_PRODUCT_OWNER,
                    "Listing " + product.id() + " belongs to another seller");
        }
    }

    private static boolean canWrite(Product product, Caller caller) {
        return caller.has(Permissions.PRODUCT_WRITE_ANY)
                || caller.has(Permissions.PRODUCT_WRITE) && caller.sellerId() != null
                        && caller.sellerId().equals(product.sellerId());
    }

    private ValidatedAttributes validate(ListingDetails details) {
        String formPath;
        try {
            formPath = categories.get(details.category()).formPath();
        }
        catch (CategoryNotFoundException ex) {
            throw new ProductStateException("UNKNOWN_CATEGORY", ex.getMessage());
        }
        return attributes.validate(formPath, details.attributes() != null ? details.attributes() : Map.of());
    }
}
