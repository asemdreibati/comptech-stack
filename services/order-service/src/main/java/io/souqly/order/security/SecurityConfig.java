package io.souqly.order.security;

import io.souqly.platform.security.SouqlyHttpSecurity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static io.souqly.order.security.Permissions.ORDER_PLACE;
import static io.souqly.order.security.Permissions.ORDER_READ_ANY;

/** Buyers check out and read their own orders; ownership is checked per order. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SouqlyHttpSecurity souqly) throws Exception {
        return souqly.build(http, requests -> requests
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAuthority(ORDER_PLACE)
                .requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*")
                        .hasAnyAuthority(ORDER_PLACE, ORDER_READ_ANY));
    }
}
