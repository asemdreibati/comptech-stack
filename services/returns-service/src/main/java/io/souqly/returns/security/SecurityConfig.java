package io.souqly.returns.security;

import io.souqly.platform.security.SouqlyHttpSecurity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static io.souqly.returns.security.Permissions.RETURN_ARBITRATE;
import static io.souqly.returns.security.Permissions.RETURN_DECIDE;
import static io.souqly.returns.security.Permissions.RETURN_HANDLE;
import static io.souqly.returns.security.Permissions.RETURN_REQUEST;

/** Each party acts only at its own stage; who may act on which return is checked per task. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SouqlyHttpSecurity souqly) throws Exception {
        return souqly.build(http, requests -> requests
                .requestMatchers(HttpMethod.GET, "/api/v1/return-tasks")
                        .hasAnyAuthority(RETURN_DECIDE, RETURN_ARBITRATE, RETURN_HANDLE)
                .requestMatchers(HttpMethod.POST, "/api/v1/returns/*/seller-decision").hasAuthority(RETURN_DECIDE)
                .requestMatchers(HttpMethod.POST, "/api/v1/returns/*/arbitration").hasAuthority(RETURN_ARBITRATE)
                .requestMatchers(HttpMethod.POST, "/api/v1/returns/*/received", "/api/v1/returns/*/inspection")
                        .hasAuthority(RETURN_HANDLE)
                .requestMatchers(HttpMethod.POST, "/api/v1/returns", "/api/v1/returns/*/response")
                        .hasAuthority(RETURN_REQUEST)
                .requestMatchers(HttpMethod.GET, "/api/v1/returns", "/api/v1/returns/*")
                        .hasAnyAuthority(RETURN_REQUEST, RETURN_DECIDE, RETURN_ARBITRATE, RETURN_HANDLE));
    }
}
