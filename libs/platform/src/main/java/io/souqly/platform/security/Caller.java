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

    /** Someone calling a public endpoint without a token. */
    public static Caller anonymous() {
        return new Caller(null, null, Set.of());
    }

    /** @param authentication the request's authentication, or {@code null} for anonymous calls */
    public static Caller from(Authentication authentication) {
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken)) {
            return anonymous();
        }
        Set<String> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
        String sellerId = ((JwtAuthenticationToken) authentication).getToken().getClaimAsString("seller_id");
        return new Caller(authentication.getName(), sellerId, permissions);
    }
}
