package io.souqly.search.index;

import java.io.IOException;
import java.io.InputStream;

import io.souqly.search.config.SearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Makes sure the products alias exists, pointing at a versioned index ({@code products-v1}).
 * Readers and writers only ever use the alias, so a mapping change can be rolled out by building
 * {@code products-v2} from the compacted topics and swapping the alias, with no downtime.
 */
@Component
public class IndexManager implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final OpenSearchClient opensearch;
    private final JsonMapper json;
    private final SearchProperties.OpenSearch config;

    public IndexManager(OpenSearchClient opensearch, JsonMapper json, SearchProperties properties) {
        this.opensearch = opensearch;
        this.json = json;
        this.config = properties.opensearch();
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (opensearch.send(HttpMethod.HEAD, "/_alias/" + config.alias(), null).ok()) {
            return;
        }
        ObjectNode definition = loadDefinition();
        ((ObjectNode) definition.get("settings").get("index")).put("number_of_replicas", config.replicas());
        definition.putObject("aliases").putObject(config.alias()).put("is_write_index", true);
        var created = opensearch.send(HttpMethod.PUT, "/" + config.initialIndex(), definition);
        if (!created.ok() && !created.body().path("error").path("type").asString("")
                .equals("resource_already_exists_exception")) {
            throw new IllegalStateException("Could not create index " + config.initialIndex() + ": " + created.body());
        }
        log.info("Created index {} behind alias {}", config.initialIndex(), config.alias());
    }

    private ObjectNode loadDefinition() {
        try (InputStream in = new ClassPathResource("opensearch/products-index.json").getInputStream()) {
            return (ObjectNode) json.readTree(in);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
