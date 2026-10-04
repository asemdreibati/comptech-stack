package io.souqly.catalog.image;

import java.time.Duration;
import java.util.Optional;

import io.souqly.catalog.config.CatalogProperties;
import io.souqly.platform.storage.ObjectStorage;
import io.souqly.platform.storage.ObjectStorage.PresignedRequest;
import io.souqly.platform.storage.ObjectStorage.StoredObject;
import io.souqly.platform.storage.S3Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Product images in an S3-compatible store. The bucket has two areas:
 * <ul>
 *   <li>{@code uploads/}: private landing zone for direct browser uploads; anything left there
 *       unverified is deleted by a lifecycle rule after a day;</li>
 *   <li>{@code public/}: verified images, world-readable and immutable, served via a CDN.</li>
 * </ul>
 */
@Component
public class ImageStorage implements SmartInitializingSingleton, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);
    static final String UPLOADS = "uploads/";
    static final String PUBLIC = "public/";

    private final CatalogProperties.Storage config;
    private final ObjectStorage store;

    public ImageStorage(CatalogProperties properties) {
        this.config = properties.storage();
        this.store = new ObjectStorage(new S3Settings(config.endpoint(), config.publicEndpoint(), config.region(),
                config.accessKey(), config.secretKey(), config.bucket()));
    }

    public PresignedRequest presignUpload(String key, String contentType, long size, Duration ttl) {
        return store.presignUpload(key, contentType, size, ttl);
    }

    public Optional<StoredObject> stat(String key) {
        return store.stat(key);
    }

    public byte[] head(String key, int bytes) {
        return store.head(key, bytes);
    }

    /** Moves a verified upload to the public area with long-lived caching. */
    public void publish(String uploadKey, String publicKey, String contentType) {
        store.move(uploadKey, publicKey, contentType, "public, max-age=31536000, immutable");
    }

    public void delete(String key) {
        store.delete(key);
    }

    public String publicUrl(String key) {
        return config.publicBaseUrl() + "/" + key;
    }

    /** Creates the bucket, opens {@code public/} for reading and expires abandoned uploads. */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            store.ensureBucket();
            store.allowPublicRead(PUBLIC);
            store.expire(UPLOADS, 1);
        }
        catch (RuntimeException ex) {
            // Reads keep working; uploads fail until the store is reachable and this is rerun.
            log.error("Could not prepare image bucket {}: {}", config.bucket(), ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        store.close();
    }
}
