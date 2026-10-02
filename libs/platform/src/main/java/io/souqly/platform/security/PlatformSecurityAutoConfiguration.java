package io.souqly.platform.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Keycloak token validation for every service: signature (keys from the JWKS endpoint), expiry,
 * issuer and audience are all checked, then the result is cached per token. Keys are fetched
 * lazily, so a service starts even while Keycloak is briefly unavailable.
 */
@AutoConfiguration(before = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnClass(JwtDecoder.class)
@EnableConfigurationProperties({SouqlySecurityProperties.class, OAuth2ResourceServerProperties.class})
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties, Clock clock, MeterRegistry meterRegistry) {
        var config = properties.getJwt();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(config.getIssuerUri()));
        config.getAudiences().forEach(audience -> validators.add(new JwtAudienceValidator(audience)));
        var nimbus = NimbusJwtDecoder.withJwkSetUri(config.getJwkSetUri()).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return new CachingJwtDecoder(nimbus, Duration.ofSeconds(60), 10_000, clock, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    SouqlyHttpSecurity souqlyHttpSecurity(SouqlySecurityProperties properties, JsonMapper json) {
        return new SouqlyHttpSecurity(new KeycloakJwtConverter(properties.resourceClientId()),
                new ProblemDetailsSecurityHandler(json));
    }
}
