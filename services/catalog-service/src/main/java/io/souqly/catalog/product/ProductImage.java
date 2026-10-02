package io.souqly.catalog.product;

import java.time.Instant;

/**
 * @param status PENDING until the uploaded bytes are verified; READY images have a public URL;
 *               REJECTED uploads were not the image they claimed to be
 */
public record ProductImage(String imageId, Status status, String contentType, long sizeBytes, String url,
        Instant createdAt) {

    public enum Status {
        PENDING, READY, REJECTED
    }
}
