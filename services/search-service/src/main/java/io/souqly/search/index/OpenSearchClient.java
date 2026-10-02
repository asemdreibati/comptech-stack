package io.souqly.search.index;

import java.net.http.HttpClient;

import io.souqly.search.config.SearchProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A thin client over the OpenSearch REST API. Queries are plain JSON built in
 * {@code io.souqly.search.query}, which keeps them readable and matches the OpenSearch docs.
 */
@Component
public class OpenSearchClient {

    private final RestClient http;
    private final JsonMapper json;

    public OpenSearchClient(RestClient.Builder builder, JsonMapper json, SearchProperties properties) {
        var config = properties.opensearch();
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requestFactory.setReadTimeout(config.timeout());
        this.http = builder.baseUrl(config.url().toString()).requestFactory(requestFactory).build();
        this.json = json;
    }

    public record Response(int status, JsonNode body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public Response send(HttpMethod method, String path, Object body) {
        var request = http.method(method).uri(path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
        }
        return request.exchange((req, res) -> new Response(res.getStatusCode().value(),
                json.readTree(res.getBody().readAllBytes())), true);
    }

    /** Sends a bulk request; {@code ndjson} is newline-delimited JSON ending with a newline. */
    public Response bulk(String ndjson) {
        return http.post().uri("/_bulk").contentType(MediaType.parseMediaType("application/x-ndjson")).body(ndjson)
                .exchange((req, res) -> new Response(res.getStatusCode().value(),
                        json.readTree(res.getBody().readAllBytes())), true);
    }

    public String toJson(Object value) {
        return json.writeValueAsString(value);
    }
}
