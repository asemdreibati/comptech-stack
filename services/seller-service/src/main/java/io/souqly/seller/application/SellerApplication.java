package io.souqly.seller.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An application to sell on Souqly.
 *
 * @param applicantId  the applicant's Keycloak subject
 * @param sellerHandle the seller ID the applicant asks for; it becomes their {@code seller_id}
 * @param kyc          the know-your-customer details, as validated by the {@code seller-kyc} form
 * @param escalated    whether the review missed its SLA and was escalated to compliance leads
 */
public record SellerApplication(
        UUID id,
        String applicantId,
        String sellerHandle,
        Map<String, Object> kyc,
        ApplicationStatus status,
        RiskTier riskTier,
        boolean escalated,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt,
        Instant decidedAt) {

    public String kycText(String field) {
        Object value = kyc.get(field);
        return value != null ? String.valueOf(value) : null;
    }

    public boolean ownedBy(String subject) {
        return applicantId.equals(subject);
    }
}
