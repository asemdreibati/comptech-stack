package io.souqly.seller.security;

import io.souqly.platform.security.SouqlyHttpSecurity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static io.souqly.seller.security.Permissions.APPLICATION_REVIEW;
import static io.souqly.seller.security.Permissions.APPLICATION_REVIEW_SENIOR;
import static io.souqly.seller.security.Permissions.APPLICATION_SUBMIT;

/** Applicants work on their own application; compliance works the review queue. */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SouqlyHttpSecurity souqly) throws Exception {
        return souqly.build(http, requests -> requests
                .requestMatchers("/api/v1/reviews", "/api/v1/reviews/**")
                        .hasAnyAuthority(APPLICATION_REVIEW, APPLICATION_REVIEW_SENIOR)
                // Reading an application or its documents: its applicant, or a reviewer (checked per object).
                .requestMatchers(HttpMethod.GET, "/api/v1/applications/**")
                        .hasAnyAuthority(APPLICATION_SUBMIT, APPLICATION_REVIEW, APPLICATION_REVIEW_SENIOR)
                .requestMatchers("/api/v1/applications", "/api/v1/applications/**").hasAuthority(APPLICATION_SUBMIT));
    }
}
