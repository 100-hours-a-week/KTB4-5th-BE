package com.dameokja.backend.analysis.application.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.AnalysisFeedbackRepository;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisFeedbackSubmittedPayload;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaProperties;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Import({AnalysisFeedbackOutbox.class, AnalysisFeedbackQueue.class, AnalysisFeedbackOutboxWorker.class, AnalysisKafkaPublisher.class,
        AnalysisKafkaConfig.class, AnalysisFeedbackQueueIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisFeedbackOutboxIntegrationTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = BusinessTime.now(Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), BusinessTime.ZONE));
    private final AiAnalysisFeedbackRequest request = new AiAnalysisFeedbackRequest(17L, true, List.of());
    private final String prefix = UUID.randomUUID().toString();
    private final List<Long> ids = new ArrayList<>();
    @Autowired AnalysisFeedbackOutbox analysisFeedbackOutbox;
    @Autowired AnalysisFeedbackQueue analysisFeedbackQueue;
    @Autowired AnalysisFeedbackRepository analysisFeedbackRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AnalysisFeedbackOutboxWorker analysisFeedbackOutboxWorker;
    @Autowired AnalysisKafkaProperties analysisKafkaProperties;
    @MockitoBean KafkaTemplate<String, JsonNode> kafkaTemplate;

    @AfterEach
    void cleanUp() { analysisFeedbackRepository.deleteAllById(ids); }

    @Test
    void storesTheEnvelopeAndCompletesOnlyTheLeasedKafkaFeedback() {
        Long id = enqueue();
        AnalysisFeedback httpFeedback = analysisFeedbackRepository.saveAndFlush(AnalysisFeedback.pending("ai-http", "{}", NOW, NOW.plusDays(3)));
        ids.add(httpFeedback.getId());
        assertThat(analysisFeedbackQueue.leaseDue()).extracting(LeasedFeedback::id).containsExactly(httpFeedback.getId());
        LeasedFeedback leased = analysisFeedbackOutbox.leaseDue().getFirst();
        AnalysisFeedback feedback = analysisFeedbackOutbox.findLeased(leased).orElseThrow();
        EventEnvelope<AnalysisFeedbackSubmittedPayload> event = objectMapper.readValue(feedback.getMessageBody(), new TypeReference<>() {});
        assertThat(event.payload()).isEqualTo(AnalysisFeedbackSubmittedPayload.create(7L, prefix, prefix + "-0", request, Instant.parse("2026-10-19T01:00:00Z")));
        assertThat(objectMapper.readValue(feedback.getFeedbackBody(), AiAnalysisFeedbackRequest.class)).isEqualTo(request);
        assertThat(row(id)).containsEntry("lease_until", NOW.plusMinutes(5));
        analysisFeedbackOutbox.finish(leased);
        assertThat(row(id)).containsEntry("status", "DONE").containsEntry("finished_at", NOW).containsEntry("lease_token", null);
        assertThat(analysisFeedbackOutbox.findLeased(leased)).isEmpty();
        assertThat(row(httpFeedback.getId())).containsEntry("status", "SENDING");
    }

    @Test
    void retriesTheSameStoredEventOnlyAfterTheRetryDelay() {
        Long id = enqueue();
        LeasedFeedback leased = analysisFeedbackOutbox.leaseDue().getFirst();
        String message = analysisFeedbackOutbox.findLeased(leased).orElseThrow().getMessageBody();
        analysisFeedbackOutbox.retryLater(leased, AnalysisFeedbackErrorCode.PUBLISH_FAILED);
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempt_count", 1).containsEntry("next_attempt_at", NOW.plusMinutes(10));
        assertThat(analysisFeedbackOutbox.leaseDue()).isEmpty();
        update(id, "next_attempt_at", NOW);
        LeasedFeedback retried = analysisFeedbackOutbox.leaseDue().getFirst();
        assertThat(analysisFeedbackOutbox.findLeased(retried).orElseThrow().getMessageBody()).isEqualTo(message);
        analysisFeedbackOutbox.finish(retried);
        assertThat(row(id)).containsEntry("status", "DONE").containsEntry("attempt_count", 1).containsEntry("last_error_code", null);
    }

    @Test
    void rejectsExpiredLeasesBeforeAndAfterAnotherWorkerReclaimsThem() {
        Long id = enqueue();
        LeasedFeedback stale = analysisFeedbackOutbox.leaseDue().getFirst();
        update(id, "lease_until", NOW);
        Map<String, Object> expired = row(id);
        assertThat(analysisFeedbackOutbox.findLeased(stale)).isEmpty();
        applyAllUpdates(stale);
        assertThat(row(id)).isEqualTo(expired);
        LeasedFeedback current = analysisFeedbackOutbox.leaseDue().getFirst();
        assertThat(current.id()).isEqualTo(id);
        assertThat(current.token()).isNotEqualTo(stale.token());
        applyAllUpdates(stale);
        assertThat(row(id)).containsEntry("status", "SENDING").containsEntry("attempt_count", 0).containsEntry("lease_token", current.token());
        analysisFeedbackOutbox.fail(current, AnalysisFeedbackErrorCode.INVALID_EVENT);
        assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("attempt_count", 1).containsEntry("last_error_code", "INVALID_EVENT");
    }

    @Test
    void cannotReadOrUpdateAnHttpLeaseEvenWithItsValidToken() {
        AnalysisFeedback httpFeedback = analysisFeedbackRepository.saveAndFlush(AnalysisFeedback.pending("ai-http", "{}", NOW, NOW.plusDays(3)));
        ids.add(httpFeedback.getId());
        LeasedFeedback leased = analysisFeedbackQueue.leaseDue().getFirst();
        Map<String, Object> before = row(httpFeedback.getId());
        assertThat(analysisFeedbackOutbox.findLeased(leased)).isEmpty();
        applyAllUpdates(leased);
        assertThat(row(httpFeedback.getId())).isEqualTo(before);
    }

    @Test
    void recoversExpiredLeasesBeforePendingFeedbackWithinTheBatchLimit() {
        enqueue(4);
        List<LeasedFeedback> previous = analysisFeedbackOutbox.leaseDue();
        previous.forEach(lease -> update(lease.id(), "lease_until", NOW));
        assertThat(analysisFeedbackOutbox.leaseDue()).extracting(LeasedFeedback::id).containsExactlyElementsOf(previous.stream().map(LeasedFeedback::id).toList());
        assertThat(analysisFeedbackOutbox.leaseDue()).hasSize(2);
        assertThat(analysisFeedbackOutbox.leaseDue()).isEmpty();
    }

    @Test
    void expiresKafkaFeedbackWithoutTakingAnActiveLeaseOrHttpFeedback() {
        enqueue(3);
        AnalysisFeedback httpFeedback = analysisFeedbackRepository.saveAndFlush(AnalysisFeedback.pending("ai-http", "{}", NOW, NOW));
        ids.add(httpFeedback.getId());
        List<LeasedFeedback> leased = analysisFeedbackOutbox.leaseDue();
        ids.forEach(id -> update(id, "expires_at", NOW));
        update(leased.getLast().id(), "lease_until", NOW);
        analysisFeedbackOutbox.failExpired();
        assertThat(row(leased.getFirst().id())).containsEntry("status", "SENDING");
        assertThat(row(leased.getLast().id())).containsEntry("status", "FAILED").containsEntry("last_error_code", "EXPIRED");
        assertThat(row(ids.get(2))).containsEntry("status", "FAILED");
        assertThat(row(httpFeedback.getId())).containsEntry("status", "PENDING");
        analysisFeedbackOutbox.finish(leased.getFirst());
        assertThat(row(leased.getFirst().id())).containsEntry("status", "DONE");
    }

    @Test
    void rollsBackEnqueueWithTheCallersTransaction() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Long id = enqueue();
            assertThat(analysisFeedbackRepository.existsById(id)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(analysisFeedbackRepository.findAllById(ids)).isEmpty();
    }

    @Test
    void competingInstancesDoNotLeaseTheSameFeedback() throws Exception {
        enqueue(4);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<LeasedFeedback>> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return analysisFeedbackOutbox.leaseDue();
            });
            Future<List<LeasedFeedback>> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return analysisFeedbackOutbox.leaseDue();
            });
            List<Long> leasedIds = new ArrayList<>(first.get(5, TimeUnit.SECONDS).stream().map(LeasedFeedback::id).toList());
            leasedIds.addAll(second.get(5, TimeUnit.SECONDS).stream().map(LeasedFeedback::id).toList());
            assertThat(leasedIds).containsExactlyInAnyOrderElementsOf(ids).doesNotHaveDuplicates();
        }
    }

    @Test
    void skipsFeedbackLockedByAnotherTransaction() {
        enqueue(3);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT analysis_feedback_id FROM analysis_feedbacks WHERE analysis_feedback_id = ? FOR UPDATE", Long.class, ids.getFirst());
                List<LeasedFeedback> leased = assertDoesNotThrow(() -> executor.submit(analysisFeedbackOutbox::leaseDue).get(5, TimeUnit.SECONDS));
                assertThat(leased).extracting(LeasedFeedback::id).containsExactly(ids.get(1), ids.get(2));
            });
        }
        assertThat(analysisFeedbackOutbox.leaseDue()).extracting(LeasedFeedback::id).containsExactly(ids.getFirst());
    }

    @Test
    void publishesOutsideTheTransactionAndKeepsTheRowSendingUntilKafkaAcknowledges() throws Exception {
        Long id = enqueue();
        CompletableFuture<SendResult<String, JsonNode>> ack = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        when(kafkaTemplate.send(eq(analysisKafkaProperties.feedbackTopic()), eq("7"), any(JsonNode.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sent.countDown();
            return ack;
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> publication = executor.submit(analysisFeedbackOutboxWorker::sendDue);
            try {
                assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(row(id)).containsEntry("status", "SENDING");
            } finally {
                ack.complete(null);
            }
            publication.get(5, TimeUnit.SECONDS);
        }
        assertThat(row(id)).containsEntry("status", "DONE");
    }

    @Test
    void publishesTheSameJsonObjectAndUserKeyAfterRetryingABrokerFailure() {
        Long id = enqueue();
        EventEnvelope<AnalysisFeedbackSubmittedPayload> stored = objectMapper.readValue(analysisFeedbackRepository.findById(id).orElseThrow().getMessageBody(), new TypeReference<>() {});
        when(kafkaTemplate.send(eq(analysisKafkaProperties.feedbackTopic()), eq("7"), any(JsonNode.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"))).thenReturn(CompletableFuture.completedFuture(null));
        analysisFeedbackOutboxWorker.sendDue();
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("last_error_code", "PUBLISH_FAILED").containsEntry("next_attempt_at", NOW.plusMinutes(10));
        update(id, "next_attempt_at", NOW);
        analysisFeedbackOutboxWorker.sendDue();
        ArgumentCaptor<JsonNode> published = ArgumentCaptor.captor();
        verify(kafkaTemplate, times(2)).send(eq(analysisKafkaProperties.feedbackTopic()), eq("7"), published.capture());
        assertThat(published.getAllValues()).allSatisfy(json ->
                assertThat(objectMapper.readValue(json.toString(), new TypeReference<EventEnvelope<AnalysisFeedbackSubmittedPayload>>() {})).isEqualTo(stored));
        assertThat(row(id)).containsEntry("status", "DONE");
    }

    private void enqueue(int count) {
        for (int index = 0; index < count; index++) {
            enqueue();
        }
    }

    private Long enqueue() {
        String aiId = prefix + "-" + ids.size();
        analysisFeedbackOutbox.enqueue(7L, prefix, aiId, request);
        Long id = jdbcTemplate.queryForObject("SELECT analysis_feedback_id FROM analysis_feedbacks WHERE ai_analysis_id = ?", Long.class, aiId);
        ids.add(id);
        return id;
    }

    private void applyAllUpdates(LeasedFeedback leased) {
        analysisFeedbackOutbox.finish(leased);
        analysisFeedbackOutbox.retryLater(leased, AnalysisFeedbackErrorCode.PUBLISH_FAILED);
        analysisFeedbackOutbox.fail(leased, AnalysisFeedbackErrorCode.INVALID_EVENT);
    }

    private void update(Long id, String column, LocalDateTime value) {
        jdbcTemplate.update("UPDATE analysis_feedbacks SET " + column + " = ? WHERE analysis_feedback_id = ?", value, id);
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempt_count, next_attempt_at, lease_token, lease_until, last_error_code, finished_at FROM analysis_feedbacks WHERE analysis_feedback_id = ?", id);
    }
}
