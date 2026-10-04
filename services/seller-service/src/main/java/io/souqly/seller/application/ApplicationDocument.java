package io.souqly.seller.application;

import java.time.Instant;
import java.util.UUID;

public record ApplicationDocument(
        UUID id,
        UUID applicationId,
        DocumentType type,
        String contentType,
        long sizeBytes,
        Status status,
        Instant createdAt,
        Instant verifiedAt) {

    public enum Status {
        /** An upload URL was issued; nothing verified yet. */
        PENDING,
        VERIFIED,
        /** The file was not what it claimed to be, and was deleted. */
        REJECTED
    }
}
