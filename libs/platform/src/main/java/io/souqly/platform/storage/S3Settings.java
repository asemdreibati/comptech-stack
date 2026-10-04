package io.souqly.platform.storage;

import java.net.URI;

/**
 * Connection to one bucket of an S3-compatible store (MinIO locally).
 *
 * @param endpoint       how the service reaches the store
 * @param publicEndpoint how browsers reach it; presigned URLs are signed for this host
 */
public record S3Settings(URI endpoint, URI publicEndpoint, String region, String accessKey, String secretKey,
        String bucket) {
}
