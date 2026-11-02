package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.completion.AnalysisCompletionValidator;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedEvent;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaConfig;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
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
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Import({AnalysisJobAcceptance.class, AnalysisJobStateAccess.class, AnalysisCompletionPersistence.class, AnalysisCompletionValidator.class,
        UserAccessService.class, AnalysisKafkaConfig.class, AnalysisJobStateAccessTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisJobStateAccessTest extends MySqlJpaTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-31T03:00:00Z");
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Clock clock;
    private User requestingUser;
    private User anotherUser;
    private AnalysisJob analysisJob;

    @BeforeEach
    void acceptJob() {
        when(clock.instant()).thenReturn(ACCEPTED_AT);
        when(clock.withZone(BusinessTime.ZONE)).thenAnswer(invocation -> Clock.fixed(clock.instant(), BusinessTime.ZONE));
        requestingUser = userRepository.saveAndFlush(new User("작업소유자", "profile"));
        anotherUser = userRepository.saveAndFlush(new User("다른사용자", "profile"));
        analysisJob = analysisJobAcceptance.accept(requestingUser.getId(), 91L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_analysis_job_delete");
        jdbcTemplate.update("DELETE FROM analysis_outbox");
        jdbcTemplate.update("DELETE FROM analysis_job_requests");
        jdbcTemplate.update("DELETE FROM analysis_job_states");
        userRepository.deleteAllById(List.of(requestingUser.getId(), anotherUser.getId()));
    }

    @Test
    void readsOwnedJobsNewestFirstAndPreservesTheStoredTimeOffset() {
        when(clock.instant()).thenReturn(ACCEPTED_AT.plusSeconds(1));
        AnalysisJob newerAnalysisJob = analysisJobAcceptance.accept(requestingUser.getId(), 91L, "PRODUCT", List.of(AnalysisImage.pending("second", "cd".repeat(32))));
        analysisJobAcceptance.accept(anotherUser.getId(), 91L, "AUTO", List.of(AnalysisImage.pending("other", "ef".repeat(32))));
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), requestingUser.getId())).isEqualTo(analysisJob);
        assertThat(analysisJobStateAccess.findAllOwned(requestingUser.getId())).containsExactly(newerAnalysisJob, analysisJob);
        assertThatThrownBy(() -> analysisJobStateAccess.findOwned(analysisJob.id(), anotherUser.getId()))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(GlobalExceptionCode.FORBIDDEN));
        assertThat(analysisJobStateAccess.find("missing")).isEmpty();
        assertThatThrownBy(() -> analysisJobStateAccess.findOwned("missing", requestingUser.getId()))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(AnalysisExceptionCode.NOT_FOUND));
    }

    @Test
    void hidesResultsAtTheirRetentionDeadlineAndRejectsFurtherChanges() {
        completeJob();
        when(clock.instant()).thenReturn(ACCEPTED_AT.plus(Duration.ofDays(7)));
        assertThat(analysisJobStateAccess.find(analysisJob.id())).isEmpty();
        assertThat(analysisJobStateAccess.findAllOwned(requestingUser.getId())).isEmpty();
        assertThatThrownBy(() -> analysisJobStateAccess.update(analysisJob.id(), AnalysisJob::acknowledgeNotification))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(AnalysisExceptionCode.NOT_FOUND));
        assertStoredCounts(1);
    }

    @Test
    void updatesTheSnapshotStatusAndRetentionTogetherWithoutWritingForANoOp() {
        AnalysisJob completedAnalysisJob = completeJob();
        AnalysisJob acknowledgedAnalysisJob = analysisJobStateAccess.update(analysisJob.id(), AnalysisJob::acknowledgeNotification);
        Long storedVersion = jdbcTemplate.queryForObject("SELECT version FROM analysis_job_states", Long.class);
        assertThat(acknowledgedAnalysisJob.notificationPending()).isFalse();
        assertThat(acknowledgedAnalysisJob.expiresAt()).isEqualTo(completedAnalysisJob.expiresAt());
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(acknowledgedAnalysisJob);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_job_states", String.class)).isEqualTo("COMPLETED");
        analysisJobStateAccess.update(analysisJob.id(), AnalysisJob::acknowledgeNotification);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM analysis_job_states", Long.class)).isEqualTo(storedVersion);
    }

    @Test
    void persistsTheNewTerminalStatusAndItsRetentionAlongsideTheSnapshot() {
        AnalysisCompletion failedCompletion = new AnalysisCompletion(AnalysisStatus.FAILED, ACCEPTED_AT.atZone(BusinessTime.ZONE).toOffsetDateTime(),
                null, AnalysisFailureCode.ANALYSIS_EXPIRED.toFailure());
        AnalysisJob failedAnalysisJob = analysisJobStateAccess.update(analysisJob.id(), currentAnalysisJob -> currentAnalysisJob.fail(
                analysisJob.analysisImages().getFirst().requestId(), failedCompletion, ACCEPTED_AT.plus(Duration.ofDays(7)).atZone(BusinessTime.ZONE).toOffsetDateTime()));
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(failedAnalysisJob);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_job_states", String.class)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT expires_at FROM analysis_job_states", LocalDateTime.class))
                .isEqualTo(failedAnalysisJob.expiresAt().toLocalDateTime());
    }

    @Test
    void deletesOnlyTheOwnedJobAndItsOutboxAndRequestLinks() {
        AnalysisJob repeatedAnalysisJob = analysisJobAcceptance.accept(requestingUser.getId(), 91L, "AUTO", analysisJob.analysisImages());
        assertThatThrownBy(() -> analysisJobStateAccess.deleteOwned(analysisJob.id(), anotherUser.getId()))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(GlobalExceptionCode.FORBIDDEN));
        assertStoredCounts(2);
        analysisJobStateAccess.deleteOwned(analysisJob.id(), requestingUser.getId());
        assertStoredCounts(1);
        assertThat(analysisJobStateAccess.find(analysisJob.id())).isEmpty();
        assertThat(analysisJobStateAccess.find(repeatedAnalysisJob.id())).contains(repeatedAnalysisJob);
    }

    @Test
    void restoresChildrenWhenDeletingTheJobFails() {
        jdbcTemplate.execute("CREATE TRIGGER fail_analysis_job_delete BEFORE DELETE ON analysis_job_states "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'delete-rollback'");
        assertThatThrownBy(() -> analysisJobStateAccess.deleteOwned(analysisJob.id(), requestingUser.getId())).hasStackTraceContaining("delete-rollback");
        assertStoredCounts(1);
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(analysisJob);
    }

    @Test
    void joinsTheCallersTransactionForChangesAndDeletion() {
        completeJob();
        AnalysisJob completedAnalysisJob = analysisJobStateAccess.find(analysisJob.id()).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
            analysisJobStateAccess.update(analysisJob.id(), AnalysisJob::acknowledgeNotification);
            analysisJobStateAccess.deleteOwned(analysisJob.id(), requestingUser.getId());
            entityManager.flush();
            assertStoredCounts(0);
            transactionStatus.setRollbackOnly();
        });
        assertStoredCounts(1);
        assertThat(analysisJobStateAccess.find(analysisJob.id())).contains(completedAnalysisJob);
    }

    @Test
    void preservesBothChangesWhenDifferentInstancesUpdateTheSameJob() throws Exception {
        completeJob();
        CyclicBarrier startUpdates = new CyclicBarrier(2);
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            Future<AnalysisJob> notificationUpdate = executorService.submit(() -> {
                startUpdates.await(5, TimeUnit.SECONDS);
                return analysisJobStateAccess.update(analysisJob.id(), AnalysisJob::acknowledgeNotification);
            });
            Future<AnalysisJob> imageUpdate = executorService.submit(() -> {
                startUpdates.await(5, TimeUnit.SECONDS);
                return analysisJobStateAccess.update(analysisJob.id(), currentAnalysisJob -> currentAnalysisJob.publishImageEvent(analysisJob.analysisImages().getFirst().requestId()));
            });
            notificationUpdate.get(5, TimeUnit.SECONDS);
            imageUpdate.get(5, TimeUnit.SECONDS);
        }
        AnalysisJob updatedAnalysisJob = analysisJobStateAccess.find(analysisJob.id()).orElseThrow();
        assertThat(updatedAnalysisJob.notificationPending()).isFalse();
        assertThat(updatedAnalysisJob.imageEventPublished(analysisJob.analysisImages().getFirst().requestId())).isTrue();
    }

    private AnalysisJob completeJob() {
        return analysisCompletionPersistence.apply(new AnalysisCompletedEvent(UUID.randomUUID().toString(), new AnalysisCompletedEvent.Payload("AnalysisCompleted",
                ACCEPTED_AT, analysisJob.analysisImages().getFirst().requestId(), requestingUser.getId(), "ai-result", AnalysisStatus.COMPLETED,
                ACCEPTED_AT, new AnalysisCompletionSuccess(AnalysisDocumentType.OTHER, List.of()), null))).getFirst();
    }

    private void assertStoredCounts(int count) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states", Integer.class)).isEqualTo(count);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests", Integer.class)).isEqualTo(count);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox", Integer.class)).isEqualTo(count);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
    }
}
