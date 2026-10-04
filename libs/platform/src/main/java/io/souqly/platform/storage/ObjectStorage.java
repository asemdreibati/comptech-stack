package io.souqly.platform.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

/**
 * One bucket in an S3-compatible store, used for direct browser uploads: the service hands out
 * short-lived presigned URLs and never handles the bytes itself, then verifies what arrived.
 * Only the S3 API is used, so the store can be MinIO, AWS S3 or any compatible service.
 */
public class ObjectStorage implements AutoCloseable {

    private final S3Settings config;
    private final S3Client s3;
    private final S3Presigner presigner;

    public ObjectStorage(S3Settings config) {
        this.config = config;
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
        // Browsers talk to the public endpoint, and the signature covers the host name.
        this.presigner = S3Presigner.builder()
                .endpointOverride(config.publicEndpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    /** A URL and the headers the browser must send with it. */
    public record PresignedRequest(String url, Map<String, String> headers, Instant expiresAt) {
    }

    public record StoredObject(long size, String contentType) {
    }

    public String bucket() {
        return config.bucket();
    }

    /**
     * A URL the browser can PUT exactly one file to. Content type and length are part of the
     * signature, so the store rejects a different type or size.
     */
    public PresignedRequest presignUpload(String key, String contentType, long size, Duration ttl) {
        var presigned = presigner.presignPutObject(request -> request
                .signatureDuration(ttl)
                .putObjectRequest(put -> put.bucket(config.bucket()).key(key)
                        .contentType(contentType).contentLength(size)));
        return new PresignedRequest(presigned.url().toString(), withoutHost(presigned.signedHeaders()),
                presigned.expiration());
    }

    /** A URL that lets its holder read one private object until it expires. */
    public PresignedRequest presignDownload(String key, Duration ttl) {
        var presigned = presigner.presignGetObject(request -> request
                .signatureDuration(ttl)
                .getObjectRequest(get -> get.bucket(config.bucket()).key(key)));
        return new PresignedRequest(presigned.url().toString(), withoutHost(presigned.signedHeaders()),
                presigned.expiration());
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

    /** Moves an object, replacing its metadata. */
    public void move(String fromKey, String toKey, String contentType, String cacheControl) {
        s3.copyObject(request -> request
                .sourceBucket(config.bucket()).sourceKey(fromKey)
                .destinationBucket(config.bucket()).destinationKey(toKey)
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(contentType)
                .cacheControl(cacheControl));
        delete(fromKey);
    }

    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(config.bucket()).key(key));
    }

    /** Creates the bucket if it does not exist. */
    public void ensureBucket() {
        try {
            s3.headBucket(request -> request.bucket(config.bucket()));
        }
        catch (NoSuchBucketException ex) {
            s3.createBucket(request -> request.bucket(config.bucket()));
        }
    }

    /** Lets anyone read objects under {@code prefix}; everything else stays private. */
    public void allowPublicRead(String prefix) {
        s3.putBucketPolicy(request -> request.bucket(config.bucket()).policy("""
                {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"AWS": ["*"]},
                  "Action": ["s3:GetObject"], "Resource": ["arn:aws:s3:::%s/%s*"]}]}
                """.formatted(config.bucket(), prefix)));
    }

    /**
     * Deletes objects under {@code prefix} once they are {@code days} old (abandoned uploads).
     * Replaces the bucket's lifecycle configuration with this one rule.
     */
    public void expire(String prefix, int days) {
        s3.putBucketLifecycleConfiguration(request -> request.bucket(config.bucket())
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(List.of(
                        LifecycleRule.builder().id("expire-" + prefix.replace("/", ""))
                                .filter(LifecycleRuleFilter.builder().prefix(prefix).build())
                                .expiration(expiration -> expiration.days(days))
                                .status(ExpirationStatus.ENABLED)
                                .build()))
                        .build()));
    }

    @Override
    public void close() {
        presigner.close();
        s3.close();
    }

    private static Map<String, String> withoutHost(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("host"))
                .collect(Collectors.toMap(Map.Entry::getKey, header -> String.join(",", header.getValue())));
    }
}
