package io.souqly.search.index;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Search cannot answer without its cluster, so a red cluster takes the instance out of rotation. */
@Component("opensearch")
class OpenSearchHealthIndicator implements HealthIndicator {

    private final OpenSearchClient opensearch;

    OpenSearchHealthIndicator(OpenSearchClient opensearch) {
        this.opensearch = opensearch;
    }

    @Override
    public Health health() {
        try {
            var response = opensearch.send(HttpMethod.GET, "/_cluster/health", null);
            String status = response.body().path("status").asString("unknown");
            var builder = "red".equals(status) || !response.ok() ? Health.down() : Health.up();
            return builder.withDetail("cluster", status).build();
        }
        catch (RuntimeException ex) {
            return Health.down(ex).build();
        }
    }
}
