package io.souqly.catalog.security;

import io.souqly.platform.security.SouqlyHttpSecurity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static io.souqly.catalog.security.Permissions.CATEGORY_MANAGE;
import static io.souqly.catalog.security.Permissions.PRODUCT_WRITE;
import static io.souqly.catalog.security.Permissions.PRODUCT_WRITE_ANY;

/**
 * Reading categories and products needs no token; the service itself hides listings that are
 * not live from everyone but their owner. Writing needs product permissions, and ownership is
 * checked per listing.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SouqlyHttpSecurity souqly) throws Exception {
        return souqly.build(http, requests -> requests
                .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/categories/*",
                        "/api/v1/categories/*/form", "/api/v1/products/*").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/v1/categories/*").hasAuthority(CATEGORY_MANAGE)
                .requestMatchers("/api/v1/products", "/api/v1/products/**")
                        .hasAnyAuthority(PRODUCT_WRITE, PRODUCT_WRITE_ANY));
    }
}
