package io.souqly.inventory.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is making a request, as far as object-level authorization cares.
 *
 * @param subject  the token subject, for audit
 * @param sellerId the seller account from the {@code seller_id} claim, or {@code null}
 * @param actsForAnySeller whether the caller holds {@link Permissions#STOCK_WRITE_ANY}
 */
public record Caller(String subject, String sellerId, boolean actsForAnySeller) {

    public static Caller from(Authentication authentication) {
        boolean any = authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.STOCK_WRITE_ANY.equals(a.getAuthority()));
        String sellerId = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaimAsString("seller_id")
                : null;
        return new Caller(authentication.getName(), sellerId, any);
    }
}
