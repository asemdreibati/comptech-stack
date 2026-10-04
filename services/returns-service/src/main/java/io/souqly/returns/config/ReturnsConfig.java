package io.souqly.returns.config;

import java.net.http.HttpClient;

import io.souqly.platform.payments.PaymentGateway;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ReturnsConfig {

    /** Keyed by return ID and carrying the full state, so compaction keeps each return's latest. */
    @Bean
    NewTopic returnsTopic(ReturnsProperties properties) {
        var topics = properties.topics();
        return TopicBuilder.name(topics.returns()).partitions(topics.partitions()).replicas(topics.replicas())
                .compact().build();
    }

    @Bean
    PaymentGateway paymentGateway(RestClient.Builder builder, ReturnsProperties properties, JsonMapper json) {
        var payments = properties.payments();
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(payments.timeout()).build());
        requestFactory.setReadTimeout(payments.timeout());
        return new PaymentGateway(builder.clone()
                .baseUrl(payments.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + payments.apiKey())
                .build(), json);
    }

    @Bean
    OpenAPI returnsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Souqly Returns API")
                .version("v1")
                .description("Return requests, seller decisions, disputes, parcel receipt and inspection."));
    }
}
