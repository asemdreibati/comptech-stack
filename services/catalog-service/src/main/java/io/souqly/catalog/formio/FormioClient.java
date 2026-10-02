package io.souqly.catalog.formio;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.souqly.catalog.config.CatalogProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Talks to the Form.io server that holds each category's attribute schema.
 *
 * <p>Validation uses Form.io's own engine ({@code POST /{form}/submission?dryrun=1}): the same rules
 * the seller portal renders in the browser are enforced on the server, and nothing is stored in
 * Form.io. Its response is the cleaned submission, with fields the form does not define removed.
 * Form definitions are cached briefly, since every listing write needs one.
 */
@Component
public class FormioClient {

    /** Form.io answers 440 when its JWT has expired. */
    private static final int LOGIN_TIMEOUT = 440;

    private final RestClient http;
    private final JsonMapper json;
    private final CatalogProperties.Formio config;
    private final Cache<String, FormDefinition> forms;
    private volatile String token;

    public FormioClient(RestClient.Builder builder, JsonMapper json, CatalogProperties properties) {
        this.config = properties.formio();
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        requestFactory.setReadTimeout(config.timeout());
        this.http = builder.baseUrl(config.baseUrl().toString()).requestFactory(requestFactory).build();
        this.json = json;
        this.forms = Caffeine.newBuilder().expireAfterWrite(config.schemaCacheTtl()).maximumSize(500).build();
    }

    public FormDefinition form(String path) {
        FormDefinition cached = forms.getIfPresent(path);
        if (cached != null) {
            return cached;
        }
        FormDefinition form = authenticated(jwt -> http.get().uri("/{path}", path)
                .header("x-jwt-token", jwt)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        throw new FormNotFoundException(path);
                    }
                    ensureSuccess(response.getStatusCode(), "load form " + path);
                    return FormDefinition.parse(path, json.readTree(response.getBody()));
                }, false));
        forms.put(path, form);
        return form;
    }

    /**
     * Validates attributes against a form without storing anything.
     *
     * @return the cleaned data: only the form's fields, with Form.io's type coercion applied
     * @throws AttributeValidationException listing the failing fields
     */
    public Map<String, Object> validate(String path, Map<String, Object> attributes) {
        String body = json.writeValueAsString(Map.of("data", attributes));
        return authenticated(jwt -> http.post().uri("/{path}/submission?dryrun=1", path)
                .header("x-jwt-token", jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 400) {
                        throw new AttributeValidationException(fieldErrors(json.readTree(response.getBody())));
                    }
                    if (status.value() == 404) {
                        throw new FormNotFoundException(path);
                    }
                    ensureSuccess(status, "validate against " + path);
                    JsonNode data = json.readTree(response.getBody()).path("data");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> clean = json.convertValue(data, LinkedHashMap.class);
                    return clean;
                }, false));
    }

    private static List<FieldError> fieldErrors(JsonNode error) {
        List<FieldError> errors = new ArrayList<>();
        for (JsonNode detail : error.path("details")) {
            List<String> path = new ArrayList<>();
            detail.path("path").forEach(segment -> path.add(segment.asString()));
            errors.add(new FieldError(String.join(".", path), detail.path("message").asString()));
        }
        if (errors.isEmpty()) {
            errors.add(new FieldError("", error.path("message").asString("Invalid attributes")));
        }
        return errors;
    }

    /** Runs a call with a valid session, logging in again once if the session has expired. */
    private <T> T authenticated(Function<String, T> call) {
        try {
            try {
                return call.apply(currentToken());
            }
            catch (SessionExpiredException ex) {
                token = null;
                return call.apply(currentToken());
            }
        }
        catch (ResourceAccessException ex) {
            throw new SchemaUnavailableException("Form.io is unreachable", ex);
        }
    }

    private String currentToken() {
        String current = token;
        if (current == null) {
            synchronized (this) {
                if (token == null) {
                    token = login();
                }
                current = token;
            }
        }
        return current;
    }

    private String login() {
        String body = json.writeValueAsString(
                Map.of("data", Map.of("email", config.email(), "password", config.password())));
        return http.post().uri("/admin/login").contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, response) -> {
                    String jwt = response.getHeaders().getFirst("x-jwt-token");
                    if (!response.getStatusCode().is2xxSuccessful() || jwt == null) {
                        throw new SchemaUnavailableException(
                                "Form.io login failed with status " + response.getStatusCode().value(), null);
                    }
                    return jwt;
                }, true);
    }

    private static void ensureSuccess(HttpStatusCode status, String action) throws IOException {
        if (status.value() == 401 || status.value() == LOGIN_TIMEOUT) {
            throw new SessionExpiredException();
        }
        if (!status.is2xxSuccessful()) {
            throw new SchemaUnavailableException("Form.io failed to " + action + ": status " + status.value(), null);
        }
    }

    private static final class SessionExpiredException extends RuntimeException {

        SessionExpiredException() {
            super(null, null, false, false);
        }
    }
}
