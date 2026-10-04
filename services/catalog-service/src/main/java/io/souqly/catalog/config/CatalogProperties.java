package io.souqly.catalog.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("souqly.catalog")
public record CatalogProperties(
        @DefaultValue Topics topics,
        Storage storage,
        @DefaultValue Images images) {

    public record Topics(
            @DefaultValue("catalog.products.v1") String products,
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicas) {
    }

    /**
     * Any S3-compatible object store (MinIO locally).
     *
     * @param endpoint       how this service reaches the store
     * @param publicEndpoint how browsers reach it; upload URLs are signed for this host
     * @param publicBaseUrl  where published images are served from (a CDN in production)
     */
    public record Storage(
            URI endpoint,
            URI publicEndpoint,
            String publicBaseUrl,
            @DefaultValue("us-east-1") String region,
            String accessKey,
            String secretKey,
            @DefaultValue("souqly-product-images") String bucket) {
    }

    public record Images(
            @DefaultValue("5242880") long maxBytes,
            @DefaultValue("10") int maxPerProduct,
            @DefaultValue("PT5M") Duration uploadUrlTtl,
            @DefaultValue({"image/jpeg", "image/png", "image/webp"}) List<String> contentTypes) {
    }
}
