package io.souqly.catalog.product;

import java.time.Clock;
import java.util.UUID;

import io.souqly.catalog.config.CatalogProperties;
import io.souqly.catalog.image.ImageStorage;
import io.souqly.catalog.image.ImageStorage.PresignedUpload;
import io.souqly.catalog.image.ImageTypes;
import io.souqly.catalog.product.ProductExceptions.InvalidImageException;
import io.souqly.catalog.product.ProductExceptions.ProductStateException;
import io.souqly.platform.security.Caller;

import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Listing images, uploaded straight from the browser to object storage:
 * <ol>
 *   <li>the seller declares type and size and gets a short-lived signed upload URL;</li>
 *   <li>the browser PUTs the file to a private landing area (the service never handles the bytes);</li>
 *   <li>the seller confirms; the service checks the stored size and reads the file's first bytes to
 *       prove it really is the declared image type, then moves it to the public area.</li>
 * </ol>
 * A file that fails verification is deleted, so storage never serves something it did not check.
 */
@Service
public class ProductImageService {

    private final ProductService products;
    private final ImageStorage storage;
    private final CatalogProperties.Images config;
    private final Clock clock;

    public ProductImageService(ProductService products, ImageStorage storage, CatalogProperties properties,
            Clock clock) {
        this.products = products;
        this.storage = storage;
        this.config = properties.images();
        this.clock = clock;
    }

    public record UploadTicket(ProductImage image, PresignedUpload upload) {
    }

    public UploadTicket requestUpload(String productId, String contentType, long sizeBytes, Caller caller) {
        if (!config.contentTypes().contains(contentType)) {
            throw new ProductStateException("UNSUPPORTED_IMAGE_TYPE",
                    "Images must be one of " + String.join(", ", config.contentTypes()));
        }
        if (sizeBytes <= 0 || sizeBytes > config.maxBytes()) {
            throw new ProductStateException("IMAGE_TOO_LARGE", "Images must be at most " + config.maxBytes() + " bytes");
        }
        Product product = products.load(productId);
        ProductService.requireWriteAccess(product, caller);

        var image = new ProductImage(UUID.randomUUID().toString(), ProductImage.Status.PENDING, contentType, sizeBytes,
                null, clock.instant());
        // "No element at index max-1" means fewer than max images, checked atomically with the push.
        var added = products.apply(where("_id").is(productId).and("images." + (config.maxPerProduct() - 1)).exists(false),
                new Update().push("images", image));
        if (added == null) {
            throw new ProductStateException("IMAGE_LIMIT",
                    "A listing can have at most " + config.maxPerProduct() + " images");
        }
        return new UploadTicket(image, storage.presignUpload(uploadKey(productId, image.imageId()), contentType,
                sizeBytes, config.uploadUrlTtl()));
    }

    public Product completeUpload(String productId, String imageId, Caller caller) {
        Product product = products.load(productId);
        ProductService.requireWriteAccess(product, caller);
        ProductImage image = product.images().stream()
                .filter(candidate -> candidate.imageId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new ProductStateException("IMAGE_NOT_FOUND", "No image " + imageId + " on this listing"));
        switch (image.status()) {
            case READY -> {
                return product;
            }
            case REJECTED -> throw new ProductStateException("IMAGE_REJECTED", "This upload was rejected; start a new one");
            case PENDING -> {
                // verified below
            }
        }

        String uploadKey = uploadKey(productId, imageId);
        var stored = storage.stat(uploadKey).orElseThrow(() -> new ProductStateException("UPLOAD_MISSING",
                "Nothing has been uploaded yet; PUT the file to the upload URL first"));
        String problem = verify(image, uploadKey, stored);
        var pending = where("_id").is(productId)
                .and("images").elemMatch(where("imageId").is(imageId).and("status").is(ProductImage.Status.PENDING));
        if (problem != null) {
            storage.delete(uploadKey);
            products.apply(pending, new Update().set("images.$.status", ProductImage.Status.REJECTED));
            throw new InvalidImageException(problem);
        }

        String publicKey = "public/" + productId + "/" + imageId + "." + ImageTypes.extension(image.contentType());
        storage.publish(uploadKey, publicKey, image.contentType());
        Product updated = products.apply(pending, new Update()
                .set("images.$.status", ProductImage.Status.READY)
                .set("images.$.url", storage.publicUrl(publicKey)));
        // Null means a concurrent confirmation of the same image got there first.
        return updated != null ? updated : products.load(productId);
    }

    private String verify(ProductImage image, String uploadKey, ImageStorage.StoredObject stored) {
        if (stored.size() != image.sizeBytes()) {
            return "Uploaded file is " + stored.size() + " bytes but " + image.sizeBytes() + " were declared";
        }
        var actual = ImageTypes.detect(storage.head(uploadKey, ImageTypes.SNIFF_BYTES));
        if (actual.isEmpty()) {
            return "Uploaded file is not a JPEG, PNG or WebP image";
        }
        if (!actual.get().equals(image.contentType())) {
            return "Uploaded file is " + actual.get() + " but was declared as " + image.contentType();
        }
        return null;
    }

    static String uploadKey(String productId, String imageId) {
        return "uploads/" + productId + "/" + imageId;
    }
}
