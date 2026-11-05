package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.domain.RecommendationOutbox.Status;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.kafka.RecommendationKafkaPublisher;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import({RecommendationOutboxWorker.class, RecommendationOutboxQueue.class, RecommendationKafkaPublisher.class, RecommendationOutboxWorkerTest.JsonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecommendationOutboxWorkerTest extends MySqlJpaTest {
    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");
    private static final String TOPIC = "test.recommendation.requested.v1";
    private static final String MESSAGE_KEY = "42";
    private static final String MESSAGE_BODY = "{\"messageId\":\"saved-event\",\"payload\":{\"userId\":42}}";

    @Autowired RecommendationOutboxWorker recommendationOutboxWorker;
    @Autowired RecommendationOutboxRepository recommendationOutboxRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JsonMapper jsonMapper;
    @MockitoBean KafkaTemplate<String, JsonNode> kafkaTemplate;
    @MockitoBean Clock clock;
    private Long refrigeratorId;
    private Long outboxId;

    @BeforeEach
    void saveKafkaOutbox() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            Refrigerator refrigerator = new Refrigerator("추천 발행 냉장고", "2026-11");
            entityManager.persist(refrigerator);
            refrigeratorId = refrigerator.getId();
            RecommendationOutbox outbox = new RecommendationOutbox(UUID.randomUUID().toString(), refrigerator, "request", "{}", true);
            outbox.prepareKafka(TOPIC, MESSAGE_KEY, MESSAGE_BODY, BusinessTime.now(clock));
            outboxId = recommendationOutboxRepository.saveAndFlush(outbox).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            recommendationOutboxRepository.deleteById(outboxId);
            recommendationOutboxRepository.flush();
            entityManager.remove(entityManager.getReference(Refrigerator.class, refrigeratorId));
        });
    }

    @Test
    void sendsStoredMessageOutsideTheTransactionAndMarksSentOnlyAfterAck() throws Exception {
        CompletableFuture<SendResult<String, JsonNode>> brokerAck = new CompletableFuture<>();
        CountDownLatch sendStarted = new CountDownLatch(1);
        when(kafkaTemplate.send(TOPIC, MESSAGE_KEY, jsonMapper.readTree(MESSAGE_BODY))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sendStarted.countDown();
            return brokerAck;
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> publication = executor.submit(recommendationOutboxWorker::publishBatch);
            try {
                assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(outbox().getStatus()).isEqualTo(Status.PUBLISHING);
            } finally {
                brokerAck.complete(null);
            }
            publication.get(5, TimeUnit.SECONDS);
        }
        assertThat(outbox().getStatus()).isEqualTo(Status.SENT);
    }

    @Test
    void reschedulesAFailedSendAndPublishesTheSameMessageAfterTheDelay() {
        when(kafkaTemplate.send(TOPIC, MESSAGE_KEY, jsonMapper.readTree(MESSAGE_BODY)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")), CompletableFuture.completedFuture(null));
        recommendationOutboxWorker.publishBatch();
        assertThat(outbox().getStatus()).isEqualTo(Status.PENDING);
        recommendationOutboxWorker.publishBatch();
        assertThat(outbox().getStatus()).isEqualTo(Status.PENDING);
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        recommendationOutboxWorker.publishBatch();
        assertThat(outbox().getStatus()).isEqualTo(Status.SENT);
    }

    private RecommendationOutbox outbox() {
        return recommendationOutboxRepository.findById(outboxId).orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }
}
