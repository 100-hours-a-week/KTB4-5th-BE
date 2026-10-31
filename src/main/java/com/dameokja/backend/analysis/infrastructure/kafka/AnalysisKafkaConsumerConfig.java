package com.dameokja.backend.analysis.infrastructure.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class AnalysisKafkaConsumerConfig {
    private static final int PARTITION_BY_KEY = -1;

    // 역직렬화 실패 원문은 JSON 문자열이나 Base64로 바꾸지 않고 그대로 보존한다.
    @Bean(defaultCandidate = false)
    KafkaTemplate<Object, Object> analysisDeadLetterBytesKafkaTemplate(ProducerFactory<Object, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory, Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    @Bean
    DefaultErrorHandler analysisCompletionErrorHandler(@Qualifier("kafkaTemplate") KafkaTemplate<Object, Object> kafkaTemplate,
            @Qualifier("analysisDeadLetterBytesKafkaTemplate") KafkaTemplate<Object, Object> analysisDeadLetterBytesKafkaTemplate,
            AnalysisKafkaProperties analysisKafkaProperties) {
        Map<Class<?>, KafkaOperations<?, ?>> kafkaTemplates = new LinkedHashMap<>();
        kafkaTemplates.put(byte[].class, analysisDeadLetterBytesKafkaTemplate);
        kafkaTemplates.put(Object.class, kafkaTemplate);
        DeadLetterPublishingRecoverer deadLetterPublishingRecoverer = new DeadLetterPublishingRecoverer(kafkaTemplates,
                (consumerRecord, exception) -> new TopicPartition(analysisKafkaProperties.completionDltTopic(), PARTITION_BY_KEY));
        deadLetterPublishingRecoverer.setFailIfSendResultIsError(true);
        // DB 장애는 횟수 제한으로 버리지 않는다. 적용할 수 없는 이벤트만 DLT 발행 확인 후 넘긴다.
        DefaultErrorHandler defaultErrorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer,
                new FixedBackOff(analysisKafkaProperties.completionRetryDelay().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        defaultErrorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return defaultErrorHandler;
    }
}
