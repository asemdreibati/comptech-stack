package io.souqly.seller.config;

import java.net.http.HttpClient;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class SellerConfig {

    /** Keyed by application ID and carrying the full state, so compaction keeps each one's latest. */
    @Bean
    NewTopic applicationsTopic(OnboardingProperties properties) {
        var topics = properties.topics();
        return TopicBuilder.name(topics.applications()).partitions(topics.partitions()).replicas(topics.replicas())
                .compact().build();
    }

    /**
     * Client credentials for the Keycloak admin API. Works on workflow job threads, where there is
     * no HTTP request or user.
     */
    @Bean
    OAuth2AuthorizedClientManager serviceTokenManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    RestClient keycloakAdminHttp(RestClient.Builder builder, OnboardingProperties properties,
            OAuth2AuthorizedClientManager serviceTokenManager) {
        var tokens = new OAuth2ClientHttpRequestInterceptor(serviceTokenManager);
        tokens.setClientRegistrationIdResolver(request -> "keycloak-admin");
        var service = new AnonymousAuthenticationToken("seller-service", "seller-service",
                AuthorityUtils.createAuthorityList("ROLE_SERVICE"));
        tokens.setPrincipalResolver(request -> service);
        var keycloak = properties.keycloak();
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(keycloak.timeout()).build());
        requestFactory.setReadTimeout(keycloak.timeout());
        return builder.clone()
                .baseUrl(keycloak.adminUrl() + "/admin/realms/" + keycloak.realm())
                .requestFactory(requestFactory)
                .requestInterceptor(tokens)
                .build();
    }

    @Bean
    OpenAPI sellerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Souqly Seller API")
                .version("v1")
                .description("Seller applications (KYC) and the compliance review queue."));
    }
}
