package io.souqly.search.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("souqly.search")
public record SearchProperties(OpenSearch opensearch, @DefaultValue Topics topics, @DefaultValue Retry retry) {

    /**
     * @param alias        what readers and writers use; reindexing swaps the index behind it
     * @param initialIndex the concrete index created when the alias does not exist yet
     */
    public record OpenSearch(
            URI url,
            @DefaultValue("products") String alias,
            @DefaultValue("products-v1") String initialIndex,
            @DefaultValue("0") int replicas,
            @DefaultValue("PT5S") Duration timeout) {
    }

    /** Topics this service reads; it owns only their dead-letter topics. */
    public record Topics(
            @DefaultValue("catalog.products.v1") String products,
            @DefaultValue("inventory.stock-levels.v1") String stockLevels,
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicas) {
    }

    /** How long a failing record is retried before it is parked on the dead-letter topic. */
    public record Retry(@DefaultValue("PT30S") Duration maxElapsed) {
    }
}
