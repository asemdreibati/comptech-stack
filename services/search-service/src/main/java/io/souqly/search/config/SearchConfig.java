package io.souqly.search.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
class SearchConfig {

    static final String DEAD_LETTER_SUFFIX = "-dlt";

    /**
     * A record that keeps failing is retried with backoff (30 seconds by default), then parked on
     * {@code <topic>-dlt} with its error in the headers, so one bad event never stalls a partition.
     * Index writes are idempotent, so retrying a partly applied batch is safe.
     */
    @Bean
    DefaultErrorHandler indexingErrorHandler(KafkaTemplate<?, ?> kafka, SearchProperties properties) {
        var backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(10_000);
        backOff.setMaxElapsedTime(properties.retry().maxElapsed().toMillis());
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafka), backOff);
    }

    @Bean
    NewTopic productsDeadLetterTopic(SearchProperties properties) {
        return deadLetter(properties.topics().products(), properties.topics());
    }

    @Bean
    NewTopic stockLevelsDeadLetterTopic(SearchProperties properties) {
        return deadLetter(properties.topics().stockLevels(), properties.topics());
    }

    private static NewTopic deadLetter(String topic, SearchProperties.Topics topics) {
        // Same partition count: a dead letter keeps the partition number of its original record.
        return TopicBuilder.name(topic + DEAD_LETTER_SUFFIX).partitions(topics.partitions())
                .replicas(topics.replicas()).build();
    }

    @Bean
    OpenAPI searchOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Souqly Search API")
                .version("v1")
                .description("Product search with Arabic and English text, filters, facets and autocomplete."));
    }
}
