package io.souqly.inventory.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CachingJwtDecoderTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private final AtomicInteger delegateCalls = new AtomicInteger();
    private Instant now = NOW;

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private final JwtDecoder delegate = token -> {
        delegateCalls.incrementAndGet();
        if (token.startsWith("bad")) {
            throw new BadJwtException("invalid signature");
        }
        return Jwt.withTokenValue(token).header("alg", "RS256").subject("svc")
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(30)).build();
    };

    private final CachingJwtDecoder decoder =
            new CachingJwtDecoder(delegate, Duration.ofSeconds(60), 100, clock, new SimpleMeterRegistry());

    @Test
    void validatesEachTokenOnce() {
        decoder.decode("token-a");
        decoder.decode("token-a");
        decoder.decode("token-a");

        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    void neverServesATokenPastItsExpiry() {
        decoder.decode("token-a");
        now = NOW.plusSeconds(30);

        assertThatExceptionOfType(JwtValidationException.class).isThrownBy(() -> decoder.decode("token-a"));
    }

    @Test
    void neverCachesFailures() {
        for (int i = 0; i < 2; i++) {
            assertThatExceptionOfType(BadJwtException.class).isThrownBy(() -> decoder.decode("bad-token"));
        }
        assertThat(delegateCalls).hasValue(2);
    }
}
