package io.souqly.seller.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param reviewSla           how long compliance has to review before the task is escalated
 * @param informationDeadline how long an applicant has to answer a request for information
 * @param identifierKey       secret key for hashing bank, phone and licence identifiers in events
 */
@ConfigurationProperties("souqly.onboarding")
public record OnboardingProperties(
        @DefaultValue("PT48H") Duration reviewSla,
        @DefaultValue("P14D") Duration informationDeadline,
        String identifierKey,
        @DefaultValue Topics topics,
        Keycloak keycloak,
        Storage storage,
        @DefaultValue Documents documents) {

    public record Topics(
            @DefaultValue("sellers.applications.v1") String applications,
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicas) {
    }

    /** The Keycloak admin API, used with this service's own client-credentials token. */
    public record Keycloak(URI adminUrl, String realm, @DefaultValue("PT5S") Duration timeout) {
    }

    /** A private bucket: documents are only ever read through short-lived signed URLs. */
    public record Storage(
            URI endpoint,
            URI publicEndpoint,
            @DefaultValue("us-east-1") String region,
            String accessKey,
            String secretKey,
            @DefaultValue("souqly-seller-documents") String bucket) {
    }

    public record Documents(
            @DefaultValue("10485760") long maxBytes,
            @DefaultValue("PT5M") Duration uploadUrlTtl,
            @DefaultValue("PT5M") Duration downloadUrlTtl,
            @DefaultValue({"application/pdf", "image/jpeg", "image/png"}) List<String> contentTypes) {
    }
}
