package io.souqly.inventory.security;

import io.souqly.platform.security.SouqlyHttpSecurity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static io.souqly.inventory.security.Permissions.FLASH_SALE_MANAGE;
import static io.souqly.inventory.security.Permissions.RESERVATION_WRITE;
import static io.souqly.inventory.security.Permissions.STOCK_READ;
import static io.souqly.inventory.security.Permissions.STOCK_WRITE;
import static io.souqly.inventory.security.Permissions.STOCK_WRITE_ANY;

/**
 * Who may call which endpoint. Token validation, error responses and the deny-by-default rule
 * come from the platform baseline ({@link SouqlyHttpSecurity}).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SouqlyHttpSecurity souqly) throws Exception {
        return souqly.build(http, requests -> requests
                .requestMatchers(HttpMethod.GET, "/api/v1/stock/*").hasAuthority(STOCK_READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/stock/*/restock").hasAnyAuthority(STOCK_WRITE, STOCK_WRITE_ANY)
                .requestMatchers("/api/v1/flash-sales/**").hasAuthority(FLASH_SALE_MANAGE)
                .requestMatchers("/api/v1/reservations", "/api/v1/reservations/**").hasAuthority(RESERVATION_WRITE));
    }
}
