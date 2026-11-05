package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.completion.AnalysisCompletionValidator;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({RefrigeratorAccessService.class, UserAccessService.class, AnalysisKafkaConfig.class, AnalysisJobAcceptance.class, AnalysisJobStateAccess.class,
        AnalysisCompletionPersistence.class, AnalysisCompletionValidator.class, AnalysisDatabaseJobExpirer.class, AnalysisDatabaseJobExpirerTest.Config.class})
@TestPropertySource(properties = {"analysis.job-timeout=PT2M", "analysis.result-ttl=PT3H"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisDatabaseJobExpirerTest extends MySqlJpaTest {
    private static final String OWNED_JOBS = "analysis_job_id IN (SELECT analysis_job_id FROM analysis_job_states WHERE user_id = ?)";
    private static final Instant ACCEPTED_AT = Instant.parse("2026-11-05T03:00:00Z");
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration RESULT_TTL = Duration.ofHours(3);
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired AnalysisDatabaseJobExpirer analysisDatabaseJobExpirer;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Clock clock;
    private User requestingUser;
    private AnalysisJob analysisJob;

    @BeforeEach
    void acceptJob() {
        when(clock.instant()).thenReturn(ACCEPTED_AT);
        when(clock.withZone(BusinessTime.ZONE)).thenAnswer(invocation -> Clock.fixed(clock.instant(), BusinessTime.ZONE));
        requestingUser = userRepository.saveAndFlush(new User("만료" + UUID.randomUUID().toString().substring(0, 6), "profile"));
        AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, requestingUser.getId());
        analysisJob = analysisJobAcceptance.accept(requestingUser.getId(), "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)),
                AnalysisImage.pending("second", "cd".repeat(32)), AnalysisImage.pending("third", "ef".repeat(32))));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_analysis_expiration");
        jdbcTemplate.update("DELETE FROM analysis_outbox WHERE " + OWNED_JOBS, requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE " + OWNED_JOBS, requestingUser.getId());
        jdbcTemplate.update("DELETE FROM analysis_job_states WHERE user_id = ?", requestingUser.getId());
        AnalysisRefrigeratorFixture.cleanUp(jdbcTemplate, requestingUser.getId());
        userRepository.deleteById(requestingUser.getId());
    }

    @Test
    void leavesTheJobUntouchedBeforeTheConfiguredDeadline() {
        Long version = value("version", Long.class);
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT).minusMillis(1));
        assertThat(analysisDatabaseJobExpirer.expireIfOverdue(analysisJob)).isEqualTo(analysisJob);
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(analysisJob);
        assertThat(value("version", Long.class)).isEqualTo(version);
        assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isNull();
    }

    @Test
    void expiresAtTheDeadlineAndAtomicallyStoresResultsRetentionAndNotification() {
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT));
        AnalysisJob expiredJob = analysisDatabaseJobExpirer.expireIfOverdue(analysisJob);
        assertThat(expiredJob.analysisImages()).allSatisfy(image -> {
            assertThat(image.completion().status()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(image.completion().error().code()).isEqualTo("ANALYSIS_EXPIRED");
            assertThat(image.completion().error().retryable()).isTrue();
            assertThat(image.completion().completedAt().toInstant()).isEqualTo(clock.instant());
        });
        assertThat(expiredJob.notificationPending()).isTrue();
        assertThat(expiredJob.expiresAt().toInstant()).isEqualTo(clock.instant().plus(RESULT_TTL));
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(expiredJob);
        assertThat(value("status", String.class)).isEqualTo("FAILED");
        assertThat(value("expires_at", LocalDateTime.class)).isEqualTo(expiredJob.expiresAt().toLocalDateTime());
        assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isEqualTo(BusinessTime.now(clock));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox WHERE analysis_job_id = ?", Integer.class, analysisJob.id())).isEqualTo(3);
    }

    @Test
    void preservesCompletedImagesAndTheExistingNotificationLease() {
        AnalysisJob partiallyCompletedJob = analysisCompletionPersistence.apply(event(0)).getFirst();
        String requestId = analysisJob.analysisImages().get(1).requestId();
        analysisJobStateAccess.update(analysisJob.id(), currentJob -> currentJob.register(requestId, "ai-1"));
        LocalDateTime leaseUntil = BusinessTime.now(clock).plusHours(1);
        jdbcTemplate.update("UPDATE analysis_job_states SET notification_lease_token = 'active-lease', notification_next_attempt_at = ? WHERE analysis_job_id = ?",
                leaseUntil, analysisJob.id());
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT));
        AnalysisJob expiredJob = analysisDatabaseJobExpirer.expireIfOverdue(analysisJob);
        assertThat(expiredJob.analysisImages().getFirst()).isEqualTo(partiallyCompletedJob.analysisImages().getFirst());
        assertThat(expiredJob.analysisImages().get(1).aiAnalysisId()).isEqualTo("ai-1");
        assertThat(expiredJob.analysisImages().subList(1, 3)).allSatisfy(image -> assertThat(image.completion().status()).isEqualTo(AnalysisStatus.FAILED));
        assertThat(value("status", String.class)).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isEqualTo(leaseUntil);
        assertThat(value("notification_lease_token", String.class)).isEqualTo("active-lease");
    }

    @Test
    void repeatedExpirationWithAStaleSnapshotDoesNotExtendRetentionOrReschedule() {
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT));
        AnalysisJob expiredJob = analysisDatabaseJobExpirer.expireIfOverdue(analysisJob);
        jdbcTemplate.update("UPDATE analysis_job_states SET notification_next_attempt_at = NULL WHERE analysis_job_id = ?", analysisJob.id());
        Long version = value("version", Long.class);
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT).plusSeconds(1));
        assertThat(analysisDatabaseJobExpirer.expireIfOverdue(analysisJob)).isEqualTo(expiredJob);
        assertThat(value("version", Long.class)).isEqualTo(version);
        assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isNull();
        assertThat(analysisDatabaseJobExpirer.expireIfOverdue(expiredJob)).isEqualTo(expiredJob);
    }

    @Test
    void rollsBackResultChangesIfNotificationPersistenceFails() {
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT));
        jdbcTemplate.execute("CREATE TRIGGER fail_analysis_expiration BEFORE UPDATE ON analysis_job_states FOR EACH ROW BEGIN "
                + "IF NEW.notification_next_attempt_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'expiration-rollback'; END IF; END");
        assertThatThrownBy(() -> analysisDatabaseJobExpirer.expireIfOverdue(analysisJob)).hasStackTraceContaining("expiration-rollback");
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(analysisJob);
        assertThat(value("status", String.class)).isEqualTo("QUEUED");
        assertThat(value("expires_at", LocalDateTime.class)).isNull();
        assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isNull();
    }

    @Test
    void joinsTheCallersTransactionForResultAndNotificationChanges() {
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT));
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            analysisDatabaseJobExpirer.expireIfOverdue(analysisJob);
            entityManager.flush();
            assertThat(value("status", String.class)).isEqualTo("FAILED");
            assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isEqualTo(BusinessTime.now(clock));
            transactionStatus.setRollbackOnly();
        });
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(analysisJob);
        assertThat(value("notification_next_attempt_at", LocalDateTime.class)).isNull();
    }

    @Test
    void rechecksTheLatestStateAfterAConcurrentCompletionCommits() throws Exception {
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT));
        CountDownLatch expirationStarted = new CountDownLatch(1);
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            Future<AnalysisJob> expiration = new TransactionTemplate(transactionManager).execute(transactionStatus -> {
                analysisJobStateAccess.findForUpdate(analysisJob.id());
                Future<AnalysisJob> future = executorService.submit(() -> {
                    expirationStarted.countDown();
                    return analysisDatabaseJobExpirer.expireIfOverdue(analysisJob);
                });
                await(expirationStarted);
                for (int index = 0; index < 3; index++) {
                    analysisCompletionPersistence.apply(event(index));
                }
                return future;
            });
            AnalysisJob completedJob = analysisJobStateAccess.find(analysisJob.id()).orElseThrow();
            assertThat(expiration.get(5, TimeUnit.SECONDS)).isEqualTo(completedJob);
            assertThat(completedJob.analysisImages()).allSatisfy(image -> assertThat(image.completion().status()).isEqualTo(AnalysisStatus.COMPLETED));
            assertThat(value("status", String.class)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void preservesTheTimeoutResultWhenALateCompletionArrives() {
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(JOB_TIMEOUT));
        AnalysisJob expiredJob = analysisDatabaseJobExpirer.expireIfOverdue(analysisJob);
        assertThatThrownBy(() -> analysisCompletionPersistence.apply(event(0)))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(AnalysisExceptionCode.RESULT_CONFLICT));
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(expiredJob);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private EventEnvelope<AnalysisCompletedPayload> event(int imageIndex) {
        return EventEnvelope.create(new AnalysisCompletedPayload(clock.instant(), analysisJob.analysisImages().get(imageIndex).requestId(),
                requestingUser.getId(), analysisJob.refrigeratorId(), "ai-" + imageIndex, AnalysisStatus.COMPLETED, clock.instant(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.OTHER, List.of()), null));
    }

    private <T> T value(String column, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM analysis_job_states WHERE analysis_job_id = ?", type, analysisJob.id());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
    }
}
