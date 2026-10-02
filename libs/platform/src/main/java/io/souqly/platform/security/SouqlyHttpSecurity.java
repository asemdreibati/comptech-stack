package io.souqly.platform.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The security baseline every Souqly service shares, so each one only declares who may call
 * which endpoint:
 * <ul>
 *   <li>stateless OAuth2 resource server validating Keycloak JWTs on every request;</li>
 *   <li>authorities are this service's Keycloak client roles;</li>
 *   <li>401 and 403 answered as problem details;</li>
 *   <li>health, metrics and API docs open (they are served to the cluster, never via the gateway);</li>
 *   <li>anything a service does not explicitly allow is denied.</li>
 * </ul>
 */
public class SouqlyHttpSecurity {

    private static final String[] OPERATIONAL_ENDPOINTS = {
            "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private final KeycloakJwtConverter converter;
    private final ProblemDetailsSecurityHandler problems;

    public SouqlyHttpSecurity(KeycloakJwtConverter converter, ProblemDetailsSecurityHandler problems) {
        this.converter = converter;
        this.problems = problems;
    }

    public SecurityFilterChain build(HttpSecurity http,
            Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> rules)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token APIs, no cookies
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(OPERATIONAL_ENDPOINTS).permitAll();
                    rules.customize(requests);
                    requests.anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }
}
