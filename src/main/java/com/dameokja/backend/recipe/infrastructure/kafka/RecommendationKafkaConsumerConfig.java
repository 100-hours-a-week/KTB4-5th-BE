package com.dameokja.backend.recipe.infrastructure.kafka;

import com.dameokja.backend.global.common.EventEnvelope;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.type.TypeReference;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "recommendation.kafka.completion-enabled", havingValue = "true")
public class RecommendationKafkaConsumerConfig {
    private static final int PARTITION_BY_KEY = -1;

    @Bean(defaultCandidate = false)
    KafkaTemplate<Object, Object> recommendationDeadLetterBytesKafkaTemplate(ProducerFactory<Object, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory, Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    @Bean(defaultCandidate = false)
    DefaultErrorHandler recommendationCompletionErrorHandler(@Qualifier("kafkaTemplate") KafkaTemplate<Object, Object> jsonKafkaTemplate,
            @Qualifier("recommendationDeadLetterBytesKafkaTemplate") KafkaTemplate<Object, Object> deadLetterBytesKafkaTemplate,
            @Value("${recommendation.kafka.completion-dlt-topic}") String completionDeadLetterTopic, @Value("${recommendation.kafka.completion-retry-delay:5s}") Duration completionRetryDelay) {
        Map<Class<?>, KafkaOperations<?, ?>> deadLetterKafkaTemplates = new LinkedHashMap<>();
        deadLetterKafkaTemplates.put(byte[].class, deadLetterBytesKafkaTemplate);
        deadLetterKafkaTemplates.put(Object.class, jsonKafkaTemplate);
        DeadLetterPublishingRecoverer deadLetterPublishingRecoverer = new DeadLetterPublishingRecoverer(deadLetterKafkaTemplates,
                (failedRecord, exception) -> new TopicPartition(completionDeadLetterTopic, PARTITION_BY_KEY));
        deadLetterPublishingRecoverer.setFailIfSendResultIsError(true);
        DefaultErrorHandler completionErrorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, new FixedBackOff(completionRetryDelay.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        completionErrorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return completionErrorHandler;
    }

    @Bean(defaultCandidate = false)
    ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<RecommendationCompletedPayload>> recommendationCompletionKafkaListenerContainerFactory(
            KafkaProperties kafkaProperties, @Qualifier("recommendationCompletionErrorHandler") DefaultErrorHandler completionErrorHandler) {
        JacksonJsonDeserializer<EventEnvelope<RecommendationCompletedPayload>> completionEventDeserializer = new JacksonJsonDeserializer<>(
                new TypeReference<EventEnvelope<RecommendationCompletedPayload>>() {}, false);
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<RecommendationCompletedPayload>> listenerContainerFactory = new ConcurrentKafkaListenerContainerFactory<>();
        listenerContainerFactory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(kafkaProperties.buildConsumerProperties(),
                new StringDeserializer(), new ErrorHandlingDeserializer<>(completionEventDeserializer)));
        listenerContainerFactory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
        listenerContainerFactory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        listenerContainerFactory.setCommonErrorHandler(completionErrorHandler);
        return listenerContainerFactory;
    }
}
