package com.dameokja.backend.analysis.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;

class AnalysisKafkaErrorHandlerTest {
    private final KafkaTemplate<Object, Object> json = mock(KafkaTemplate.class);
    private final KafkaTemplate<Object, Object> bytes = mock(KafkaTemplate.class);
    // 재시도 분류를 검증하므로 테스트에서는 backoff 대기만 생략한다.
    private final DefaultErrorHandler handler = new AnalysisKafkaConsumerConfig().analysisCompletionErrorHandler(json, bytes,
            new AnalysisKafkaProperties("requested", "completed", "completed.dlt", Duration.ZERO, "analysis-test", "feedback"));
    private final ConsumerRecord<String, Object> record = new ConsumerRecord<>("completed", 0, 0L, "42", Map.of("messageId", "test-event"));

    @Test
    void doesNotRecoverAnInvalidEventUntilItsDeadLetterPublicationSucceeds() {
        when(json.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")),
                CompletableFuture.completedFuture(mock(SendResult.class)));
        ListenerExecutionFailedException invalid = new ListenerExecutionFailedException("listener failed",
                new IllegalArgumentException("invalid completion", new CustomException(AnalysisExceptionCode.NOT_FOUND)));
        assertThat(handler.handleOne(invalid, record, null, null)).isFalse();
        assertThat(handler.handleOne(invalid, record, null, null)).isTrue();
        verify(json, times(2)).send(argThat((ProducerRecord<Object, Object> output) ->
                output.topic().equals("completed.dlt") && output.key().equals("42") && output.value() == record.value()));
    }

    @Test
    void keepsTransientDatabaseFailuresInRetryWithoutSendingThemToDeadLetter() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(handler.handleOne(new ListenerExecutionFailedException("listener failed",
                    new TransientDataAccessResourceException("database unavailable")), record, null, null)).isFalse();
        }
        verify(json, never()).send(any(ProducerRecord.class));
        verify(bytes, never()).send(any(ProducerRecord.class));
    }

    @Test
    void sendsMalformedInputAsOriginalBytesInsteadOfJsonOrBase64() {
        byte[] raw = "{invalid-json".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, Object> malformed = new ConsumerRecord<>("completed", 0, 1L, "42", null);
        SerializationUtils.deserializationException(malformed.headers(), raw, new IllegalArgumentException("invalid json"), false);
        when(bytes.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        assertThat(handler.handleOne(new DeserializationException("invalid", raw, false, null), malformed, null, null)).isTrue();
        verify(bytes).send(argThat((ProducerRecord<Object, Object> output) ->
                output.topic().equals("completed.dlt") && java.util.Arrays.equals((byte[]) output.value(), raw)));
        verify(json, never()).send(any(ProducerRecord.class));
    }
}
