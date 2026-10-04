package io.souqly.seller.events;

import java.time.Instant;
import java.util.UUID;

import io.souqly.seller.application.ApplicationStatus;
import io.souqly.seller.application.RiskTier;

/**
 * An application's state after a workflow step, keyed by application ID. Bank account, phone and
 * trade licence appear only as keyed hashes: consumers can tell that two sellers share a bank
 * account without ever seeing it.
 *
 * @param sellerId the handle the applicant asked for; their {@code seller_id} once approved
 */
public record SellerApplicationEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        UUID applicationId,
        String applicantId,
        String sellerId,
        ApplicationStatus status,
        RiskTier riskTier,
        String country,
        String businessType,
        Identifiers identifiers,
        long version) {

    /** Keyed SHA-256 hashes of normalised identifiers. */
    public record Identifiers(String iban, String phone, String tradeLicence) {
    }
}
