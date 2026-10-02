package io.souqly.catalog.image;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.souqly.catalog.config.CatalogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
 * Only the S3 API is used, so the store can be MinIO, AWS S3 or any compatible service.
 */
@Component
public class ImageStorage implements SmartInitializingSingleton, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);
    static final String UPLOADS = "uploads/";
    static final String PUBLIC = "public/";

    private final CatalogProperties.Storage config;
    private final S3Client s3;
    private final S3Presigner presigner;

    public ImageStorage(CatalogProperties properties) {
        this.config = properties.storage();
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKey(), config.secretKey()));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        var region = Region.of(config.region());
        this.s3 = S3Client.builder()
                .endpointOverride(config.endpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                // Only send checksums when an operation demands them; not every S3-compatible store
                // supports the newer default ones.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        // Browsers upload to the public endpoint, and the signature covers the host name.
        this.presigner = S3Presigner.builder()
                .endpointOverride(config.publicEndpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    public record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
    }

    public record StoredObject(long size, String contentType) {
    }

    /**
     * A URL the browser can PUT exactly one file to. Content type and length are part of the
     * signature, so the store rejects a different type or size.
     */
    public PresignedUpload presignUpload(String key, String contentType, long size, java.time.Duration ttl) {
        var presigned = presigner.presignPutObject(request -> request
                .signatureDuration(ttl)
                .putObjectRequest(put -> put.bucket(config.bucket()).key(key)
                        .contentType(contentType).contentLength(size)));
        Map<String, String> headers = presigned.signedHeaders().entrySet().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("host"))
                .collect(Collectors.toMap(Map.Entry::getKey, header -> String.join(",", header.getValue())));
        return new PresignedUpload(presigned.url().toString(), headers, presigned.expiration());
    }

    public Optional<StoredObject> stat(String key) {
        try {
            var head = s3.headObject(request -> request.bucket(config.bucket()).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        }
        catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /** The first bytes of an object, enough to recognise its real file type. */
    public byte[] head(String key, int bytes) {
        return s3.getObjectAsBytes(request -> request.bucket(config.bucket()).key(key)
                .range("bytes=0-" + (bytes - 1))).asByteArray();
    }

    /** Moves a verified upload to the public area with long-lived caching. */
    public void publish(String uploadKey, String publicKey, String contentType) {
        s3.copyObject(request -> request
                .sourceBucket(config.bucket()).sourceKey(uploadKey)
                .destinationBucket(config.bucket()).destinationKey(publicKey)
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(contentType)
                .cacheControl("public, max-age=31536000, immutable"));
        delete(uploadKey);
    }

    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(config.bucket()).key(key));
    }

    public String publicUrl(String key) {
        return config.publicBaseUrl() + "/" + key;
    }

    /** Creates the bucket, opens {@code public/} for reading and expires abandoned uploads. */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            try {
                s3.headBucket(request -> request.bucket(config.bucket()));
            }
            catch (NoSuchBucketException ex) {
                s3.createBucket(request -> request.bucket(config.bucket()));
            }
            s3.putBucketPolicy(request -> request.bucket(config.bucket()).policy("""
                    {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"AWS": ["*"]},
                      "Action": ["s3:GetObject"], "Resource": ["arn:aws:s3:::%s/%s*"]}]}
                    """.formatted(config.bucket(), PUBLIC)));
            s3.putBucketLifecycleConfiguration(request -> request.bucket(config.bucket())
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(List.of(
                            LifecycleRule.builder().id("expire-abandoned-uploads")
                                    .filter(LifecycleRuleFilter.builder().prefix(UPLOADS).build())
                                    .expiration(expiration -> expiration.days(1))
                                    .status(ExpirationStatus.ENABLED)
                                    .build()))
                            .build()));
        }
        catch (RuntimeException ex) {
            // Reads keep working; uploads fail until the store is reachable and this is rerun.
            log.error("Could not prepare image bucket {}: {}", config.bucket(), ex.getMessage());
        }
    }

    @Override
    public void destroy() {
        presigner.close();
        s3.close();
    }
}
