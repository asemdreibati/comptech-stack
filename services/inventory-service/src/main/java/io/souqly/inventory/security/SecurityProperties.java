package io.souqly.inventory.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param resourceClientId the Keycloak client whose client roles are this service's permissions
 */
@ConfigurationProperties("souqly.security")
public record SecurityProperties(@DefaultValue("inventory-service") String resourceClientId) {
}
