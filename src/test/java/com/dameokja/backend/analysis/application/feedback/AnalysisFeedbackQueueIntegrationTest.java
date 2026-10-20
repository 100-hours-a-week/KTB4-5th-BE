package com.dameokja.backend.analysis.application.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackErrorCode;
import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackProperties;
import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackQueue;
import com.dameokja.backend.analysis.application.feedback.LeasedFeedback;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({AnalysisFeedbackQueue.class, AnalysisFeedbackQueueIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisFeedbackQueueIntegrationTest extends MySqlJpaTest {
    private static final Instant START = Instant.parse("2026-10-19T01:00:00Z");
    private static final LocalDateTime NOW = BusinessTime.now(Clock.fixed(START, ZoneOffset.UTC));
    private static final Duration RETRY_DELAY = Duration.ofMinutes(10);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration EXPIRES_AFTER = Duration.ofDays(3);
    private final AiAnalysisFeedbackRequest request = AiAnalysisFeedbackRequest.corrected("item-1", AiAnalysisFeedbackRequest.Confirmed.userConfirmed(
            "우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT, 2, null, WeightUnit.NONE, LocalDate.parse("2026-10-20")));

    @Autowired AnalysisFeedbackQueue queue;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    private TimeZone originalTimeZone;

    @BeforeEach
    void useUtcJvmTimeZone() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void cleanUp() {
        try {
            jdbcTemplate.update("DELETE FROM analysis_feedbacks");
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void storesConfirmedValuesAndFinishesTheLeasedFeedback() {
        enqueue(1);
        LeasedFeedback leased = queue.leaseDue().getFirst();
        assertThat(mapper.readValue(queue.findLeased(leased).orElseThrow().getFeedbackBody(), AiAnalysisFeedbackRequest.class)).isEqualTo(request);
        assertThat(row(leased.id())).containsEntry("lease_until", NOW.plus(LEASE_DURATION));
        assertThat(queue.leaseDue()).isEmpty();
        queue.finish(leased);
        assertThat(row(leased.id())).containsEntry("status", "DONE").containsEntry("attempt_count", 0)
                .containsEntry("lease_token", null).containsEntry("lease_until", null).containsEntry("finished_at", NOW);
        assertThat(queue.findLeased(leased)).isEmpty();
        assertThat(queue.leaseDue()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul", "America/New_York"})
    void reservesRetryAndDoesNotLeaseItBeforeTheScheduledTime(String timeZone) {
        TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
        enqueue(1);
        LeasedFeedback leased = queue.leaseDue().getFirst();
        queue.retryLater(leased, AnalysisFeedbackErrorCode.AI_BUSY);
        assertThat(row(leased.id())).containsEntry("status", "PENDING").containsEntry("attempt_count", 1).containsEntry("last_error_code", "AI_BUSY");
        assertThat(jdbcTemplate.queryForObject("SELECT next_attempt_at FROM analysis_feedbacks", LocalDateTime.class)).isEqualTo(NOW.plus(RETRY_DELAY));
        assertThat(queue.leaseDue()).isEmpty();
        jdbcTemplate.update("UPDATE analysis_feedbacks SET next_attempt_at = ?", NOW);
        queue.finish(queue.leaseDue().getFirst());
        assertThat(row(leased.id())).containsEntry("status", "DONE").containsEntry("attempt_count", 1).containsEntry("last_error_code", null);
    }

    @Test
    void recoversExpiredLeaseAndIgnoresAllUpdatesFromThePreviousWorker() {
        enqueue(1);
        LeasedFeedback stale = queue.leaseDue().getFirst();
        jdbcTemplate.update("UPDATE analysis_feedbacks SET lease_until = ?", NOW);
        LeasedFeedback current = queue.leaseDue().getFirst();
        assertThat(current.id()).isEqualTo(stale.id());
        assertThat(current.token()).isNotEqualTo(stale.token());
        assertThat(queue.findLeased(stale)).isEmpty();
        queue.finish(stale);
        queue.retryLater(stale, AnalysisFeedbackErrorCode.AI_UNAVAILABLE);
        queue.fail(stale, AnalysisFeedbackErrorCode.AI_REJECTED);
        assertThat(row(current.id())).containsEntry("status", "SENDING").containsEntry("attempt_count", 0).containsEntry("lease_token", current.token());
        queue.fail(current, AnalysisFeedbackErrorCode.AI_REJECTED);
        assertThat(row(current.id())).containsEntry("status", "FAILED").containsEntry("attempt_count", 1).containsEntry("last_error_code", "AI_REJECTED");
    }

    @Test
    void recoversExpiredLeasesBeforePendingFeedbackWithinTheBatchLimit() {
        enqueue(4);
        List<LeasedFeedback> previous = queue.leaseDue();
        jdbcTemplate.update("UPDATE analysis_feedbacks SET lease_until = ? WHERE status = 'SENDING'", NOW);
        assertThat(queue.leaseDue()).extracting(LeasedFeedback::id)
                .containsExactlyElementsOf(previous.stream().map(LeasedFeedback::id).toList());
        assertThat(queue.leaseDue()).hasSize(2);
    }

    @Test
    void expiresPendingAndAbandonedFeedbackButKeepsTheActiveLease() {
        enqueue(3);
        List<LeasedFeedback> leased = queue.leaseDue();
        assertThat(leased).hasSize(2);
        jdbcTemplate.update("UPDATE analysis_feedbacks SET expires_at = ?", NOW);
        jdbcTemplate.update("UPDATE analysis_feedbacks SET lease_until = ? WHERE analysis_feedback_id = ?", NOW, leased.getLast().id());
        queue.failExpired();
        assertThat(row(leased.getFirst().id())).containsEntry("status", "SENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks WHERE status = 'FAILED' AND last_error_code = 'EXPIRED'", Integer.class))
                .isEqualTo(2);
        assertThat(queue.leaseDue()).isEmpty();
        queue.finish(leased.getFirst());
        assertThat(row(leased.getFirst().id())).containsEntry("status", "DONE");
    }

    @RepeatedTest(5)
    void concurrentWorkersRespectBatchSizeAndNeverLeaseTheSameFeedbackTwice() {
        enqueue(6);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<LeasedFeedback>>> futures = IntStream.range(0, 4)
                    .mapToObj(index -> CompletableFuture.supplyAsync(queue::leaseDue, executor)).toList();
            List<List<LeasedFeedback>> batches = futures.stream().map(future -> future.orTimeout(10, TimeUnit.SECONDS).join()).toList();
            assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(2));
            List<Long> ids = batches.stream().flatMap(List::stream).map(LeasedFeedback::id).toList();
            assertThat(ids).hasSize(6).doesNotHaveDuplicates();
        }
        assertThat(queue.leaseDue()).isEmpty();
    }

    @Test
    void skipsFeedbackLockedByAnotherTransaction() throws Exception {
        enqueue(2);
        CompletableFuture<Void> locked = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForList("SELECT analysis_feedback_id FROM analysis_feedbacks ORDER BY analysis_feedback_id LIMIT 1 FOR UPDATE");
                locked.complete(null);
                release.orTimeout(10, TimeUnit.SECONDS).join();
            }), executor);
            try {
                locked.get(10, TimeUnit.SECONDS);
                assertThat(queue.leaseDue()).hasSize(1);
            } finally {
                release.complete(null);
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(queue.leaseDue()).hasSize(1);
    }

    private void enqueue(int count) {
        for (int index = 0; index < count; index++) {
            queue.enqueue("ai-" + index, request);
        }
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap("SELECT status, attempt_count, last_error_code, lease_token, lease_until, finished_at FROM analysis_feedbacks WHERE analysis_feedback_id = ?", id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        Clock feedbackClock() {
            return Clock.fixed(START, ZoneOffset.UTC);
        }

        @Bean
        AnalysisFeedbackProperties feedbackProperties() {
            return new AnalysisFeedbackProperties(RETRY_DELAY, LEASE_DURATION, EXPIRES_AFTER, 2);
        }

        @Bean
        ObjectMapper feedbackMapper() {
            return JsonMapper.builder().build();
        }
    }
}
