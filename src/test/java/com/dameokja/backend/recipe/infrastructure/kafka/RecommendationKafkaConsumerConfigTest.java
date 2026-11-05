package com.dameokja.backend.recipe.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.common.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;

@SuppressWarnings("unchecked")
class RecommendationKafkaConsumerConfigTest {
    private static final byte[] INVALID = "not-json".getBytes(StandardCharsets.UTF_8);
    private final RecommendationKafkaConsumerConfig config = new RecommendationKafkaConsumerConfig();
    private final KafkaTemplate<Object, Object> json = mock(KafkaTemplate.class);
    private final KafkaTemplate<Object, Object> bytes = mock(KafkaTemplate.class);
    private final Consumer<Object, Object> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    @Test
    void decodesTheCompletionContractWithoutChangingSharedConsumerSettings() throws Exception {
        KafkaProperties properties = new KafkaProperties();
        properties.getConsumer().setValueDeserializer(StringDeserializer.class);
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<RecommendationCompletedPayload>> factory =
                config.recommendationCompletionKafkaListenerContainerFactory(properties, new DefaultErrorHandler());
        byte[] completed = """
                {"messageId":"44444444-4444-4444-8444-444444444444","payload":{
                "occurredAt":"2026-11-03T03:00:15Z","requestId":"request","userId":42,"refrigeratorId":17,
                "jobId":"rjob_example","status":"FAILED","submittedAt":"2026-11-03T03:00:01Z","completedAt":"2026-11-03T03:00:15Z",
                "error":{"code":"RECIPE_TIMEOUT","message":"추천 시간 초과","retryable":true}}}
                """.getBytes(StandardCharsets.UTF_8);
        try (Deserializer<?> deserializer = factory.getConsumerFactory().getValueDeserializer()) {
            assertThat(deserializer.deserialize("test.completed", new RecordHeaders(), completed)).isInstanceOfSatisfying(EventEnvelope.class,
                    event -> assertThat(event.payload()).isInstanceOf(RecommendationCompletedPayload.class));
        }
        assertThat(properties.getConsumer().getValueDeserializer()).isEqualTo(StringDeserializer.class);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    void restoresFailedDeserializationBytesAndRequiresDltSendSuccess() {
        ConsumerRecord<String, Object> record = malformedRecord(0);
        DeserializationException failure = new DeserializationException("invalid event", INVALID, false, new IllegalArgumentException("invalid JSON"));
        when(bytes.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DefaultErrorHandler handler = config.recommendationCompletionErrorHandler(json, bytes, "test.completed.dlt", Duration.ZERO);
        assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
        verify(bytes).send(argThat((ProducerRecord<Object, Object> sent) -> sent.topic().equals("test.completed.dlt") && Arrays.equals((byte[]) sent.value(), INVALID)));
        when(bytes.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        assertThat(handler.handleOne(failure, malformedRecord(1), consumer, container)).isFalse();
        verify(json, never()).send(any(ProducerRecord.class));
    }

    @Test
    void classifiesDatabaseFailureForRetryInsteadOfDlt() {
        DefaultErrorHandler handler = config.recommendationCompletionErrorHandler(json, bytes, "test.completed.dlt", Duration.ZERO);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test.completed", 0, 0L, "7", "event");
        assertThat(handler.handleOne(new DataAccessResourceFailureException("DB unavailable"), record, consumer, container)).isFalse();
        verify(json, never()).send(any(ProducerRecord.class));
        verify(bytes, never()).send(any(ProducerRecord.class));
    }

    private ConsumerRecord<String, Object> malformedRecord(long offset) {
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<RecommendationCompletedPayload>> factory =
                config.recommendationCompletionKafkaListenerContainerFactory(new KafkaProperties(), new DefaultErrorHandler());
        ConsumerRecord<String, Object> record = new ConsumerRecord<>("test.completed", 0, offset, "7", null);
        try (Deserializer<?> deserializer = factory.getConsumerFactory().getValueDeserializer()) {
            assertThat(deserializer.deserialize(record.topic(), record.headers(), INVALID)).isNull();
        }
        return record;
    }
}
