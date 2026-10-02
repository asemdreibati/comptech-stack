package io.souqly.platform.mongo;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/** Multi-document transactions with retries on transient errors (requires a replica set). */
@AutoConfiguration(after = DataMongoAutoConfiguration.class)
@ConditionalOnClass(MongoTransactionManager.class)
@ConditionalOnBean(MongoDatabaseFactory.class)
public class PlatformMongoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    MongoTransactions mongoTransactions(MongoTransactionManager transactionManager, MeterRegistry meterRegistry) {
        return new MongoTransactions(transactionManager, meterRegistry);
    }
}
