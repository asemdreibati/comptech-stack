package io.souqly.order.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/** Outbound HTTP clients, each with its own timeout and credentials. */
@Configuration(proxyBeanMethods = false)
class ClientsConfig {

    static final String INVENTORY_REGISTRATION = "inventory";

    /**
     * Client credentials for calls this service makes on its own behalf. Works outside HTTP
     * requests too (the saga recovery runs on a scheduler thread).
     */
    @Bean
    OAuth2AuthorizedClientManager serviceTokenManager(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    RestClient inventoryHttp(RestClient.Builder builder, OrderProperties properties,
            OAuth2AuthorizedClientManager serviceTokenManager) {
        var tokens = new OAuth2ClientHttpRequestInterceptor(serviceTokenManager);
        tokens.setClientRegistrationIdResolver(request -> INVENTORY_REGISTRATION);
        // One service token, not one per buyer whose request happens to trigger the call.
        var service = new AnonymousAuthenticationToken("order-service", "order-service",
                AuthorityUtils.createAuthorityList("ROLE_SERVICE"));
        tokens.setPrincipalResolver(request -> service);
        return builder.clone()
                .baseUrl(properties.inventory().baseUrl().toString())
                .requestFactory(requestFactory(properties.inventory().timeout()))
                .requestInterceptor(tokens)
                .build();
    }

    @Bean
    RestClient paymentsHttp(RestClient.Builder builder, OrderProperties properties) {
        return builder.clone()
                .baseUrl(properties.payments().baseUrl().toString())
                .requestFactory(requestFactory(properties.payments().timeout()))
                .defaultHeader("Authorization", "Bearer " + properties.payments().apiKey())
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}
