package io.souqly.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resourceClientId the Keycloak client whose client roles are this service's permissions;
 *                         also the audience its tokens must carry
 */
@ConfigurationProperties("souqly.security")
public record SouqlySecurityProperties(String resourceClientId) {
}
