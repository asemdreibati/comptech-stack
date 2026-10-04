package io.souqly.seller.application;

import java.time.Instant;
import java.util.UUID;

/** One reviewer's decision, kept for the compliance audit trail. */
public record ReviewDecision(UUID applicationId, String stage, String reviewerId, String decision, String note,
        Instant decidedAt) {
}
