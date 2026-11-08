package com.dameokja.backend.analysis.application.completion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import com.dameokja.backend.analysis.support.AnalysisRefrigeratorFixture;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Import({RefrigeratorAccessService.class, AnalysisJobAcceptance.class, UserAccessService.class, AnalysisKafkaConfig.class,
        AnalysisCompletionPersistence.class, AnalysisCompletionValidator.class, AnalysisCompletionPersistenceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisCompletionPersistenceTest extends MySqlJpaTest {
    private static final String OWNED_JOBS = "analysis_job_id IN (SELECT analysis_job_id FROM analysis_job_states WHERE user_id = ?)";
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-30T03:00:00Z");
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean Clock clock;
    private User requestingUser;
    private final List<AnalysisImage> analysisImages = List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32)));
    private AnalysisJob analysisJob;

    @BeforeEach
    void acceptJob() {
        when(clock.instant()).thenReturn(COMPLETED_AT);
        when(clock.withZone(BusinessTime.ZONE)).thenAnswer(invocation -> Clock.fixed(clock.instant(), BusinessTime.ZONE));
        requestingUser = userRepository.saveAndFlush(new User("완료" + UUID.randomUUID().toString().substring(0, 6), "profile"));
        AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, requestingUser.getId());
        analysisJob = analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", analysisImages);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_analysis_completion");
        jdbcTemplate.update("DELETE FROM analysis_outbox WHERE " + OWNED_JOBS, requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE " + OWNED_JOBS, requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_states WHERE user_id = ?", requestingUser.getId());
        AnalysisRefrigeratorFixture.cleanUp(jdbcTemplate, requestingUser.getId());
        userRepository.deleteById(requestingUser.getId());
    }

    @Test
    void persistsResultsWithoutDependingOnPublishedOutboxRows() {
        jdbcTemplate.update("DELETE FROM analysis_outbox WHERE " + OWNED_JOBS, requestingUser.getId());
        analysisCompletionPersistence.apply(event(0, false));
        assertThat(storedJob().analysisImages().getFirst().aiAnalysisId()).isEqualTo("ai-0");
        assertThat(storedJob().expiresAt()).isNull();
        assertStatus("PROCESSING");
        analysisCompletionPersistence.apply(event(1, true));
        assertStatus("PARTIALLY_COMPLETED");
        assertThat(storedJob().finished()).isTrue();
        assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isEqualTo(BusinessTime.now(clock));
        assertThat(storedJob().notificationPending()).isTrue();
        assertThat(storedJob().expiresAt().toInstant()).isEqualTo(COMPLETED_AT.plus(Duration.ofDays(7)));
        assertThat(value("expires_at", LocalDateTime.class))
                .isEqualTo(storedJob().expiresAt().toLocalDateTime());
    }

    @Test
    void acceptsTheSameResultWithAnotherMessageIdWithoutExtendingRetentionOrChangingTheVersion() {
        analysisCompletionPersistence.apply(event(0, false));
        analysisCompletionPersistence.apply(event(1, false));
        Long storedVersion = value("version", Long.class);
        AnalysisJob completedJob = storedJob();
        when(clock.instant()).thenReturn(COMPLETED_AT.plus(Duration.ofDays(1)));
        analysisCompletionPersistence.apply(event(0, false));
        assertThat(storedJob()).isEqualTo(completedJob);
        assertThat(value("version", Long.class)).isEqualTo(storedVersion);
    }

    @Test
    void ignoresRedeliveryAfterTheResultRetentionDeadline() {
        analysisCompletionPersistence.apply(event(0, false));
        analysisCompletionPersistence.apply(event(1, false));
        AnalysisJob completedJob = storedJob();
        when(clock.instant()).thenReturn(COMPLETED_AT.plus(Duration.ofDays(7)));
        assertThat(analysisCompletionPersistence.apply(event(0, true))).isEmpty();
        assertThat(storedJob()).isEqualTo(completedJob);
    }

    @Test
    void preservesTheStoredResultWhenAnotherResultArrivesForTheSameRequest() {
        analysisCompletionPersistence.apply(event(0, false));
        AnalysisJob partiallyCompletedJob = storedJob();
        assertThatThrownBy(() -> analysisCompletionPersistence.apply(event(0, true)))
                .isInstanceOf(CustomException.class);
        assertThat(storedJob()).isEqualTo(partiallyCompletedJob);
    }

    @Test
    void rejectsAnEventForAnotherOwnerWithoutChangingTheJob() {
        AnalysisCompletedPayload payload = event(0, false).payload();
        EventEnvelope<AnalysisCompletedPayload> wrongOwnerEvent = new EventEnvelope<>(UUID.randomUUID().toString(), new AnalysisCompletedPayload(
                payload.occurredAt(), payload.requestId(), requestingUser.getId() + 1, payload.refrigeratorId(), payload.analysisId(),
                payload.status(), payload.completedAt(), payload.result(), payload.error()));
        assertThatThrownBy(() -> analysisCompletionPersistence.apply(wrongOwnerEvent)).isInstanceOf(IllegalArgumentException.class);
        assertThat(storedJob()).isEqualTo(analysisJob);
    }

    @Test
    void rejectsAnotherRefrigeratorWithoutChangingTheJob() {
        AnalysisCompletedPayload payload = event(0, false).payload();
        EventEnvelope<AnalysisCompletedPayload> wrongRefrigerator = new EventEnvelope<>(UUID.randomUUID().toString(), new AnalysisCompletedPayload(
                payload.occurredAt(), payload.requestId(), payload.userId(), payload.refrigeratorId() + 1, payload.analysisId(),
                payload.status(), payload.completedAt(), payload.result(), payload.error()));
        assertThatThrownBy(() -> analysisCompletionPersistence.apply(wrongRefrigerator)).isInstanceOf(IllegalArgumentException.class);
        assertThat(storedJob()).isEqualTo(analysisJob);
    }

    @Test
    void appliesLegacyCompletionUsingTheStoredRefrigeratorWhenTheEventOmitsIt() {
        AnalysisCompletedPayload payload = event(0, false).payload();
        EventEnvelope<AnalysisCompletedPayload> legacy = EventEnvelope.create(new AnalysisCompletedPayload(
                payload.occurredAt(), payload.requestId(), payload.userId(), null, payload.analysisId(),
                payload.status(), payload.completedAt(), payload.result(), payload.error()));
        analysisCompletionPersistence.apply(legacy);
        AnalysisJob stored = storedJob();
        assertThat(stored.refrigeratorId()).isEqualTo(analysisJob.refrigeratorId());
        assertThat(stored.analysisImages().getFirst().completion()).isEqualTo(payload.completion());
    }

    @Test
    void appliesOneRequestResultToEveryJobThatReusedTheSameImageRequest() {
        AnalysisJob repeatedJob = analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(analysisImages.getFirst()));
        assertThat(analysisCompletionPersistence.apply(event(0, false))).extracting(AnalysisJob::id)
                .containsExactlyInAnyOrder(analysisJob.id(), repeatedJob.id());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_job_states WHERE analysis_job_id = ?", String.class, repeatedJob.id()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void rollsBackAllMatchingJobsWhenPersistenceFailsPartwayThrough() {
        AnalysisJob repeatedJob = analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(analysisImages.getFirst()));
        String lastJobId = List.of(analysisJob.id(), repeatedJob.id()).stream().sorted().toList().getLast();
        jdbcTemplate.execute("CREATE TRIGGER fail_analysis_completion BEFORE UPDATE ON analysis_job_states FOR EACH ROW BEGIN "
                + "IF NEW.analysis_job_id = '" + lastJobId + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'completion-rollback'; END IF; END");
        assertThatThrownBy(() -> analysisCompletionPersistence.apply(event(0, false))).hasStackTraceContaining("completion-rollback");
        assertThat(jdbcTemplate.queryForList("SELECT status FROM analysis_job_states WHERE user_id = ?", String.class, requestingUser.getId())).containsOnly("QUEUED");
        assertThat(storedJob()).isEqualTo(analysisJob);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states WHERE user_id = ? AND notification_next_attempt_at IS NOT NULL",
                Integer.class, requestingUser.getId())).isZero();
    }

    @Test
    void keepsBothImageResultsWhenTwoConsumersCompleteTheSameJobConcurrently() throws Exception {
        CompletableFuture<Void> startCompletions = new CompletableFuture<>();
        CountDownLatch consumersReady = new CountDownLatch(2);
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            Future<?> firstCompletion = executorService.submit(() -> {
                consumersReady.countDown();
                startCompletions.join();
                analysisCompletionPersistence.apply(event(0, false));
            });
            Future<?> secondCompletion = executorService.submit(() -> {
                consumersReady.countDown();
                startCompletions.join();
                analysisCompletionPersistence.apply(event(1, false));
            });
            try {
                assertThat(consumersReady.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                startCompletions.complete(null);
            }
            firstCompletion.get(5, TimeUnit.SECONDS);
            secondCompletion.get(5, TimeUnit.SECONDS);
        }
        assertThat(storedJob().analysisImages()).allSatisfy(analysisImage -> assertThat(analysisImage.completion()).isNotNull());
        assertStatus("COMPLETED");
    }

    private EventEnvelope<AnalysisCompletedPayload> event(int imageIndex, boolean failed) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), new AnalysisCompletedPayload(COMPLETED_AT,
                analysisJob.analysisImages().get(imageIndex).requestId(), requestingUser.getId(), analysisJob.refrigeratorId(), "ai-" + imageIndex,
                failed ? AnalysisStatus.FAILED : AnalysisStatus.COMPLETED, COMPLETED_AT,
                failed ? null : new AnalysisCompletionSuccess(AnalysisDocumentType.OTHER, List.of()),
                failed ? new AnalysisCompletionFailure("ANALYSIS_UNAVAILABLE", "일시적인 분석 장애", true, null) : null));
    }

    private AnalysisJob storedJob() {
        return objectMapper.readerFor(AnalysisJob.class).without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .readValue(jdbcTemplate.queryForObject("SELECT job_body FROM analysis_job_states WHERE analysis_job_id = ?",
                String.class, analysisJob.id()));
    }

    private <T> T value(String column, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM analysis_job_states WHERE analysis_job_id = ?", type, analysisJob.id());
    }

    private void assertStatus(String status) {
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_job_states WHERE analysis_job_id = ?", String.class, analysisJob.id())).isEqualTo(status);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
    }
}
