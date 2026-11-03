package com.dameokja.backend.analysis.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.global.common.EventEnvelope;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.SerializationUtils;

class AnalysisKafkaConsumerConfigTest {
    private static final String COMPLETED = """
            {"messageId":"6832f444-9225-4b9c-b112-7d29984ef5ba","payload":{
            "occurredAt":"2026-10-29T03:00:15Z","requestId":"de59bfd4-fb02-38c3-a0fe-e5eb3bc776ad",
            "userId":42,"refrigeratorId":17,"analysisId":"ai-001","status":"FAILED",
            "completedAt":"2026-10-29T03:00:15Z","result":null,
            "error":{"code":"UNAVAILABLE","message":"재시도","retryable":true,"retryAfterMs":null}}}
            """;

    @Test
    void decodesTypedCompletionWithoutChangingTheSharedConsumerConfiguration() {
        KafkaProperties properties = new KafkaProperties();
        properties.getConsumer().setValueDeserializer(StringDeserializer.class);
        properties.getListener().setAutoStartup(false);
        properties.getListener().setAckMode(ContainerProperties.AckMode.RECORD);
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<AnalysisCompletedPayload>> factory =
                new AnalysisKafkaConsumerConfig().analysisCompletionKafkaListenerContainerFactory(properties, new DefaultErrorHandler());
        Deserializer<?> deserializer = factory.getConsumerFactory().getValueDeserializer();
        Object event = deserializer.deserialize("completed", new RecordHeaders(), COMPLETED.getBytes(StandardCharsets.UTF_8));
        assertThat(event).isInstanceOfSatisfying(EventEnvelope.class, envelope ->
                assertThat(envelope.payload()).isInstanceOfSatisfying(AnalysisCompletedPayload.class,
                        payload -> assertThat(payload.analysisId()).isEqualTo("ai-001")));
        assertThat(properties.getConsumer().getValueDeserializer()).isEqualTo(StringDeserializer.class);
        assertThat(properties.getConsumer().getGroupId()).isNull();
        deserializer.close();
    }

    @Test
    void exposesMalformedEnvelopeToTheDeserializationErrorHandler() {
        KafkaProperties properties = new KafkaProperties();
        properties.getListener().setAckMode(ContainerProperties.AckMode.RECORD);
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<AnalysisCompletedPayload>> factory =
                new AnalysisKafkaConsumerConfig().analysisCompletionKafkaListenerContainerFactory(properties, new DefaultErrorHandler());
        RecordHeaders headers = new RecordHeaders();
        Deserializer<?> deserializer = factory.getConsumerFactory().getValueDeserializer();
        assertThat(deserializer.deserialize("completed", headers, COMPLETED.replace("6832f444-9225-4b9c-b112-7d29984ef5ba", "invalid").getBytes(StandardCharsets.UTF_8)))
                .isNull();
        assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
        deserializer.close();
    }
}
