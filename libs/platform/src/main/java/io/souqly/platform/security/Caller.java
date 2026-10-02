package io.souqly.platform.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is making a request, as far as object-level authorization cares.
 *
 * @param subject     the token subject, for audit
 * @param sellerId    the seller account from the {@code seller_id} claim, or {@code null}
 * @param permissions this service's permissions held by the caller
 */
public record Caller(String subject, String sellerId, Set<String> permissions) {

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public static Caller from(Authentication authentication) {
        Set<String> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        String sellerId = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaimAsString("seller_id")
                : null;
        return new Caller(authentication.getName(), sellerId, permissions);
    }
}
