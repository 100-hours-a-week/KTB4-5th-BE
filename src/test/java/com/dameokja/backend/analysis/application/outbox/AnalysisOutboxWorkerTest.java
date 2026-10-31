package com.dameokja.backend.analysis.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({AnalysisJobAcceptance.class, UserAccessService.class, AnalysisKafkaConfig.class,
        AnalysisOutboxQueue.class, AnalysisOutboxWorker.class, AnalysisOutboxWorkerTest.Config.class})
@TestPropertySource(properties = "analysis.outbox.batch-size=2")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisOutboxWorkerTest extends MySqlJpaTest {
    private static final Instant POLL_INSTANT = Instant.parse("2026-10-29T03:00:00Z");
    private static final LocalDateTime CURRENT_TIME = BusinessTime.now(Clock.fixed(POLL_INSTANT, ZoneOffset.UTC));
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisOutboxQueue analysisOutboxQueue;
    @Autowired AnalysisOutboxWorker analysisOutboxWorker;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AnalysisKafkaPublisher analysisKafkaPublisher;
    private User requestingUser;

    @BeforeEach
    void enqueueRequest() {
        requestingUser = userRepository.saveAndFlush(new User("발행자", "profile"));
        analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM analysis_outbox");
        jdbcTemplate.update("DELETE FROM analysis_job_requests");
        jdbcTemplate.update("DELETE FROM analysis_job_states");
        userRepository.deleteById(requestingUser.getId());
    }

    @Test
    void publishesTheStoredMessageOutsideTheTransactionAndThenRecordsSuccess() throws Exception {
        String storedMessageBody = jdbcTemplate.queryForObject("SELECT message_body FROM analysis_outbox", String.class);
        CompletableFuture<Void> publicationStarted = new CompletableFuture<>();
        CompletableFuture<Void> brokerConfirmation = new CompletableFuture<>();
        when(analysisKafkaPublisher.publish(any())).thenAnswer(invocation -> {
            AnalysisOutbox analysisOutbox = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(analysisOutbox.getMessageBody()).isEqualTo(storedMessageBody);
            assertThat(analysisOutbox.getMessageKey()).isEqualTo(requestingUser.getId().toString());
            assertStatus("PUBLISHING");
            publicationStarted.complete(null);
            return brokerConfirmation;
        });
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            Future<?> publication = executorService.submit(analysisOutboxWorker::publishBatch);
            try {
                publicationStarted.get(5, TimeUnit.SECONDS);
                assertStatus("PUBLISHING");
            } finally {
                brokerConfirmation.complete(null);
            }
            publication.get(5, TimeUnit.SECONDS);
        }
        assertStatus("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("SELECT published_at FROM analysis_outbox", LocalDateTime.class)).isEqualTo(CURRENT_TIME);
    }

    @Test
    void retriesFailedPublicationWithoutReplacingTheMessageOrRepublishingBeforeItIsDue() {
        String storedMessageId = jdbcTemplate.queryForObject("SELECT message_id FROM analysis_outbox", String.class);
        when(analysisKafkaPublisher.publish(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));
        analysisOutboxWorker.publishBatch();
        assertStatus("PENDING");
        analysisOutboxWorker.publishBatch();
        verify(analysisKafkaPublisher).publish(any());
        jdbcTemplate.update("UPDATE analysis_outbox SET next_attempt_at = ?", CURRENT_TIME);
        analysisOutboxWorker.publishBatch();
        assertStatus("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("SELECT message_id FROM analysis_outbox", String.class)).isEqualTo(storedMessageId);
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM analysis_outbox", Integer.class)).isEqualTo(2);
    }

    @Test
    void recoversExpiredLeasesAndRejectsUpdatesFromThePreviousOwner() {
        AnalysisOutbox previousLease = analysisOutboxQueue.leaseBatch().getFirst();
        assertThat(analysisOutboxQueue.leaseBatch()).isEmpty();
        jdbcTemplate.update("UPDATE analysis_outbox SET lease_until = ?", CURRENT_TIME);
        analysisOutboxQueue.markPublished(previousLease);
        assertStatus("PUBLISHING");
        AnalysisOutbox currentLease = analysisOutboxQueue.leaseBatch().getFirst();
        assertThat(currentLease.getLeaseToken()).isNotEqualTo(previousLease.getLeaseToken());
        analysisOutboxQueue.markPublished(previousLease);
        analysisOutboxQueue.retryLater(previousLease);
        assertStatus("PUBLISHING");
        analysisOutboxQueue.markPublished(currentLease);
        assertStatus("PUBLISHED");
    }

    @Test
    void leasesAnotherMessageWhileTheFirstClaimTransactionStillHoldsItsLock() throws Exception {
        analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(
                AnalysisImage.pending("second", "cd".repeat(32)), AnalysisImage.pending("third", "ef".repeat(32))));
        CompletableFuture<List<Long>> firstClaimedOutboxIds = new CompletableFuture<>();
        CompletableFuture<Void> releaseFirstClaim = new CompletableFuture<>();
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            Future<?> firstClaim = executorService.submit(() -> transactionTemplate.executeWithoutResult(transactionStatus -> {
                firstClaimedOutboxIds.complete(analysisOutboxQueue.leaseBatch().stream().map(AnalysisOutbox::getId).toList());
                releaseFirstClaim.join();
            }));
            try {
                List<Long> lockedOutboxIds = firstClaimedOutboxIds.get(5, TimeUnit.SECONDS);
                assertThat(lockedOutboxIds).hasSize(2);
                Future<List<AnalysisOutbox>> secondClaim = executorService.submit(analysisOutboxQueue::leaseBatch);
                assertThat(secondClaim.get(5, TimeUnit.SECONDS)).singleElement().satisfies(outbox -> assertThat(lockedOutboxIds).doesNotContain(outbox.getId()));
            } finally {
                releaseFirstClaim.complete(null);
            }
            firstClaim.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void publishesBothRequestsBeforeAckAndRecordsSuccessWhileAnotherRequestStillWaits() throws Exception {
        analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(AnalysisImage.pending("second", "cd".repeat(32))));
        List<Long> ids = jdbcTemplate.queryForList("SELECT analysis_outbox_id FROM analysis_outbox ORDER BY analysis_outbox_id", Long.class);
        CountDownLatch started = new CountDownLatch(2);
        CompletableFuture<Void> firstAck = new CompletableFuture<>();
        CompletableFuture<Void> secondAck = new CompletableFuture<>();
        configurePublications(ids.getFirst(), started, firstAck, secondAck);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> batch = executor.submit(analysisOutboxWorker::publishBatch);
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                secondAck.complete(null);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status(ids.getLast())).isEqualTo("PUBLISHED"));
                assertThat(status(ids.getFirst())).isEqualTo("PUBLISHING");
                assertThat(batch.isDone()).isFalse();
                firstAck.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("delivery timeout"));
                batch.get(5, TimeUnit.SECONDS);
            } finally {
                firstAck.complete(null);
                secondAck.complete(null);
            }
        }
        assertThat(status(ids.getFirst())).isEqualTo("PENDING");
        assertThat(status(ids.getLast())).isEqualTo("PUBLISHED");
    }

    @Test
    void reclaimsExpiredRowsBeforePendingRowsWithoutExceedingTheBatchSize() {
        analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(
                AnalysisImage.pending("second", "cd".repeat(32)), AnalysisImage.pending("third", "ef".repeat(32))));
        List<AnalysisOutbox> initial = analysisOutboxQueue.leaseBatch();
        assertThat(initial).hasSize(2);
        jdbcTemplate.update("UPDATE analysis_outbox SET lease_until = ? WHERE status='PUBLISHING'", CURRENT_TIME);
        assertThat(analysisOutboxQueue.leaseBatch()).extracting(AnalysisOutbox::getId).containsExactlyElementsOf(initial.stream().map(AnalysisOutbox::getId).toList());
        assertThat(analysisOutboxQueue.leaseBatch()).hasSize(1);
        assertThat(analysisOutboxQueue.leaseBatch()).isEmpty();
    }

    private void configurePublications(Long firstId, CountDownLatch started, CompletableFuture<Void> firstAck, CompletableFuture<Void> secondAck) {
        when(analysisKafkaPublisher.publish(any())).thenAnswer(invocation -> {
            AnalysisOutbox outbox = invocation.getArgument(0);
            started.countDown();
            return outbox.getId().equals(firstId) ? firstAck : secondAck;
        });
    }

    private String status(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM analysis_outbox WHERE analysis_outbox_id = ?", String.class, id);
    }

    private void assertStatus(String expectedStatus) {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_outbox", String.class)).isEqualTo(expectedStatus);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean @Primary Clock outboxClock() { return Clock.fixed(POLL_INSTANT, ZoneOffset.UTC); }
    }
}
