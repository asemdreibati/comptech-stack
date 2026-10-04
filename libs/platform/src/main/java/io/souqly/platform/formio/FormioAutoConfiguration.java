package io.souqly.platform.formio;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/** Form.io validation, for services that set {@code souqly.formio.base-url}. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "souqly.formio", name = "base-url")
@EnableConfigurationProperties(FormioProperties.class)
public class FormioAutoConfiguration {

    @Bean
    FormioClient formioClient(ObjectProvider<RestClient.Builder> builder, JsonMapper json, FormioProperties properties) {
        return new FormioClient(builder.getIfAvailable(RestClient::builder), json, properties);
    }

    @Bean
    FormValidator formValidator(FormioClient formio) {
        return new FormValidator(formio);
    }
}
