package com.dameokja.backend.analysis.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.mock.MockConsumerFactory;

class AnalysisKafkaConsumerTest {
    private final AnalysisCompletionPersistence persistence = mock(AnalysisCompletionPersistence.class);
    private final AnalysisKafkaConsumer listener = new AnalysisKafkaConsumer(persistence);
    private final EventEnvelope<AnalysisCompletedPayload> event = EventEnvelope.create(new AnalysisCompletedPayload(Instant.EPOCH,
            "de59bfd4-fb02-38c3-a0fe-e5eb3bc776ad", 42L, 17L, "ai-001", AnalysisStatus.FAILED, Instant.EPOCH, null,
            new AnalysisCompletionFailure("UNAVAILABLE", "재시도", true, null)));

    @Test
    void rejectsMismatchedKeysAndMissingValuesBeforePersistence() {
        assertThatThrownBy(() -> listener.consume(record("other"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.consume(new ConsumerRecord<>("completed", 0, 0L, "42", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(persistence);
    }

    @Test
    void classifiesMissingAndConflictingJobsButPreservesDatabaseFailures() {
        for (AnalysisExceptionCode code : List.of(AnalysisExceptionCode.NOT_FOUND, AnalysisExceptionCode.RESULT_CONFLICT)) {
            CustomException failure = new CustomException(code);
            doThrow(failure).when(persistence).apply(event);
            assertThatThrownBy(() -> listener.consume(record("42"))).isInstanceOf(IllegalArgumentException.class).hasCause(failure);
        }
        TransientDataAccessResourceException databaseFailure = new TransientDataAccessResourceException("database unavailable");
        doThrow(databaseFailure).when(persistence).apply(event);
        assertThatThrownBy(() -> listener.consume(record("42"))).isSameAs(databaseFailure);
    }

    @Test
    void commitsTheRecordOffsetOnlyAfterThePersistenceCallReturns() throws Exception {
        TopicPartition partition = new TopicPartition("completed", 0);
        CompletableFuture<Long> committed = new CompletableFuture<>();
        MockConsumer<String, EventEnvelope<AnalysisCompletedPayload>> consumer = trackingConsumer(partition, committed);
        consumer.updateBeginningOffsets(Map.of(partition, 0L));
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(partition));
            consumer.addRecord(record("42"));
        });
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        when(persistence.apply(event)).thenAnswer(invocation -> {
            started.complete(null);
            release.join();
            return List.of();
        });
        ConcurrentMessageListenerContainer<String, EventEnvelope<AnalysisCompletedPayload>> container = createContainer(consumer);
        try {
            container.start();
            started.get(5, TimeUnit.SECONDS);
            assertThat(committed).isNotDone();
            release.complete(null);
            assertThat(committed.get(5, TimeUnit.SECONDS)).isEqualTo(1L);
        } finally {
            release.complete(null);
            container.stop();
        }
    }

    private ConcurrentMessageListenerContainer<String, EventEnvelope<AnalysisCompletedPayload>> createContainer(
            MockConsumer<String, EventEnvelope<AnalysisCompletedPayload>> consumer) {
        KafkaProperties properties = new KafkaProperties();
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope<AnalysisCompletedPayload>> factory =
                new AnalysisKafkaConsumerConfig().analysisCompletionKafkaListenerContainerFactory(properties, new DefaultErrorHandler());
        factory.setConsumerFactory(new MockConsumerFactory<>(() -> consumer));
        ConcurrentMessageListenerContainer<String, EventEnvelope<AnalysisCompletedPayload>> container = factory.createContainer("completed");
        container.getContainerProperties().setGroupId("analysis-completion-test");
        container.getContainerProperties().setMessageListener((MessageListener<String, EventEnvelope<AnalysisCompletedPayload>>) listener::consume);
        return container;
    }

    private MockConsumer<String, EventEnvelope<AnalysisCompletedPayload>> trackingConsumer(TopicPartition partition, CompletableFuture<Long> committed) {
        return new MockConsumer<>("earliest") {
            @Override
            public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
                super.commitSync(offsets);
                if (offsets.containsKey(partition) && offsets.get(partition).offset() == 1L) {
                    committed.complete(1L);
                }
            }
        };
    }

    private ConsumerRecord<String, EventEnvelope<AnalysisCompletedPayload>> record(String key) {
        return new ConsumerRecord<>("completed", 0, 0L, key, event);
    }
}
