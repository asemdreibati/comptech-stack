package io.souqly.seller.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The Keycloak admin API, called with this service's own token. Its service account holds only
 * {@code view-users}, {@code manage-users} and {@code view-realm}.
 *
 * <p>Granting a seller identity is idempotent: re-running it after a crash or retry finds the
 * attribute and role already in place and changes nothing.
 */
@Component
public class KeycloakAdmin {

    static final String SELLER_ID = "seller_id";
    static final String SELLER_ROLE = "seller";

    private final RestClient http;
    private final JsonMapper json;

    public KeycloakAdmin(@Qualifier("keycloakAdminHttp") RestClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    /** Whether any user (people and integrations alike) already holds this seller ID. */
    public boolean sellerIdTaken(String sellerId) {
        JsonNode users = http.get()
                .uri(uri -> uri.path("/users").queryParam("q", SELLER_ID + ":" + sellerId)
                        .queryParam("exact", true).queryParam("briefRepresentation", false).build())
                .retrieve()
                .body(JsonNode.class);
        for (JsonNode user : users) {
            for (JsonNode value : user.path("attributes").path(SELLER_ID)) {
                if (sellerId.equals(value.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gives the user the {@code seller_id} attribute and the {@code seller} realm role; their next
     * token carries both, and with them the catalog and inventory permissions of a seller.
     *
     * @throws SellerIdConflictException if the user already sells under another ID, or another user
     *                                   took this one since the application was made
     */
    public void grantSeller(String userId, String sellerId) {
        ObjectNode user = (ObjectNode) http.get().uri("/users/{id}", userId).retrieve().body(JsonNode.class);
        List<String> current = new ArrayList<>();
        user.path("attributes").path(SELLER_ID).forEach(value -> current.add(value.asString()));
        if (!current.isEmpty() && !current.equals(List.of(sellerId))) {
            throw new SellerIdConflictException("User " + userId + " already sells as " + current);
        }
        if (current.isEmpty()) {
            if (sellerIdTaken(sellerId)) {
                throw new SellerIdConflictException("Seller ID " + sellerId + " was taken by another user");
            }
            // The representation is written back whole, so other attributes and profile fields survive.
            Map<String, Object> attributes = new LinkedHashMap<>();
            user.path("attributes").properties().forEach(e -> attributes.put(e.getKey(), e.getValue()));
            attributes.put(SELLER_ID, List.of(sellerId));
            user.set("attributes", json.valueToTree(attributes));
            http.put().uri("/users/{id}", userId).contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(user)).retrieve().toBodilessEntity();
        }
        JsonNode role = http.get().uri("/roles/{name}", SELLER_ROLE).retrieve().body(JsonNode.class);
        // Adding a role the user already has is a no-op in Keycloak.
        http.post().uri("/users/{id}/role-mappings/realm", userId).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(List.of(Map.of("id", role.get("id").asString(),
                        "name", role.get("name").asString()))))
                .retrieve().toBodilessEntity();
    }

    public static class SellerIdConflictException extends RuntimeException {
        public SellerIdConflictException(String message) {
            super(message);
        }
    }
}
