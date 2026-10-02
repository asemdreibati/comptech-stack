package io.souqly.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/**
 * Remembers tokens that already passed full validation, so a service account reusing one token
 * for its whole lifetime pays for the RSA signature check once rather than on every request.
 *
 * <p>This accepts nothing the delegate would reject: the cache key is the exact token string,
 * entries live at most {@code maxAge}, and expiry is checked again on every hit. JWTs cannot be
 * revoked before they expire either way, so caching does not extend what a stolen token can do.
 * Failed validations are never cached.
 */
public class CachingJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;
    private final Cache<String, Jwt> validated;
    private final Clock clock;

    public CachingJwtDecoder(JwtDecoder delegate, Duration maxAge, long maxEntries, Clock clock, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.clock = clock;
        this.validated = Caffeine.newBuilder()
                .expireAfterWrite(maxAge)
                .maximumSize(maxEntries)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, validated, "validated_jwt");
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        Jwt cached = validated.getIfPresent(token);
        if (cached != null) {
            if (cached.getExpiresAt() != null && !clock.instant().isBefore(cached.getExpiresAt())) {
                validated.invalidate(token);
                throw new JwtValidationException("Jwt expired",
                        List.of(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Jwt expired", null)));
            }
            return cached;
        }
        Jwt jwt = delegate.decode(token);
        validated.put(token, jwt);
        return jwt;
    }
}
