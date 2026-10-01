package io.souqly.inventory.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns this service's Keycloak client roles ({@code resource_access.<client>.roles}) into
 * authorities. Realm roles and other services' client roles are deliberately ignored.
 */
class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String resourceClientId;

    KeycloakJwtConverter(String resourceClientId) {
        this.resourceClientId = resourceClientId;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null || !(resourceAccess.get(resourceClientId) instanceof Map<?, ?> client)
                || !(client.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority((String) role))
                .toList();
    }
}
