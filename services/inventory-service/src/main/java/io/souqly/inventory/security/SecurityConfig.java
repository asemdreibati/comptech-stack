package io.souqly.inventory.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import static io.souqly.inventory.security.Permissions.FLASH_SALE_MANAGE;
import static io.souqly.inventory.security.Permissions.RESERVATION_WRITE;
import static io.souqly.inventory.security.Permissions.STOCK_READ;
import static io.souqly.inventory.security.Permissions.STOCK_WRITE;
import static io.souqly.inventory.security.Permissions.STOCK_WRITE_ANY;

/**
 * Every API call needs a Keycloak access token whose signature, issuer, expiry and audience
 * ({@code inventory-service}) check out. The gateway validates tokens too, but this service
 * does not trust the network: it re-validates on every request.
 *
 * <p>Actuator endpoints are open because they are served to the cluster only; the gateway
 * never routes them.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties properties, JsonMapper json) throws Exception {
        var problems = new ProblemDetailsSecurityHandler(json);
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/stock/*").hasAuthority(STOCK_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/stock/*/restock")
                                .hasAnyAuthority(STOCK_WRITE, STOCK_WRITE_ANY)
                        .requestMatchers("/api/v1/flash-sales/**").hasAuthority(FLASH_SALE_MANAGE)
                        .requestMatchers("/api/v1/reservations", "/api/v1/reservations/**").hasAuthority(RESERVATION_WRITE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new KeycloakJwtConverter(properties.resourceClientId())))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /**
     * Signature (keys from the JWKS endpoint), expiry, issuer and audience are all checked, then
     * the result is cached per token. Keys are fetched lazily, so the service starts even when
     * Keycloak is briefly unavailable.
     */
    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties, Clock clock, MeterRegistry meterRegistry) {
        var config = properties.getJwt();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(config.getIssuerUri()));
        config.getAudiences().forEach(audience -> validators.add(new JwtAudienceValidator(audience)));
        var nimbus = NimbusJwtDecoder.withJwkSetUri(config.getJwkSetUri()).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return new CachingJwtDecoder(nimbus, Duration.ofSeconds(60), 10_000, clock, meterRegistry);
    }
}
