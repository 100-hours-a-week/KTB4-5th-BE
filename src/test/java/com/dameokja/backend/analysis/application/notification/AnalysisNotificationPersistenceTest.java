package com.dameokja.backend.analysis.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore.Lease;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedEvent;
import com.dameokja.backend.analysis.support.AnalysisDatabaseContractTestSupport;
import com.dameokja.backend.analysis.support.AnalysisDatabaseJobFixture;
import com.dameokja.backend.global.exception.CustomException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.stubbing.Answer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AnalysisNotificationPersistenceTest extends AnalysisDatabaseContractTestSupport {
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisJobStateRepository analysisJobStateRepository;
    @Autowired AnalysisDatabaseJobFixture analysisDatabaseJobFixture;
    @Autowired AnalysisPendingNotificationStore analysisPendingNotificationStore;
    @Autowired AnalysisNotificationState analysisNotificationState;
    @Autowired TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanNotificationTestData() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_notification_update");
        jdbcTemplate.update("DELETE FROM push_notifications");
        jdbcTemplate.update("DELETE FROM user_devices");
    }

    @BeforeEach
    void prepareOwnerAndPushSubscription() {
        clock.set("2026-10-31T03:00:00Z");
        analysisDatabaseJobFixture.ensureUser(7L);
        jdbcTemplate.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (7,'ANALYSIS',TRUE)");
        jdbcTemplate.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,vapid_key_version,created_at,updated_at,user_id) "
                + "VALUES ('https://push.example.com/analysis-notification','key','secret','v1','v1',NOW(6),NOW(6),7)");
    }

    @Test
    void commitsNotificationWorkTogetherWithTheCompletion() {
        AnalysisJob analysisJob = accept(1);
        complete(analysisJob, 0);
        assertThat(notificationScheduled(analysisJob)).isTrue();
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isTrue();
        assertThat(analysisPendingNotificationStore.leaseNext()).get().extracting(Lease::analysisJobId).isEqualTo(analysisJob.id());
    }

    @Test
    void rollsBackTheCompletionAndNotificationWorkTogether() {
        AnalysisJob analysisJob = accept(1);
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            complete(analysisJob, 0);
            transactionStatus.setRollbackOnly();
        });
        assertThat(notificationScheduled(analysisJob)).isFalse();
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isNull();
        assertThat(analysisPendingNotificationStore.leaseNext()).isEmpty();
    }

    @Test
    void doesNotSaveACompletionWhenItsNotificationWorkCannotBeSaved() {
        AnalysisJob analysisJob = accept(1);
        jdbcTemplate.execute("CREATE TRIGGER reject_notification_update BEFORE UPDATE ON analysis_job_states FOR EACH ROW BEGIN "
                + "IF NEW.notification_next_attempt_at IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'notification-work-failure'; END IF; END");
        assertThatThrownBy(() -> complete(analysisJob, 0)).hasStackTraceContaining("notification-work-failure");
        assertThat(notificationScheduled(analysisJob)).isFalse();
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isNull();
    }

    @Test
    void createsPushWorkAndAcknowledgesTheNotificationTogetherWithoutDuplicateJobs() {
        AnalysisJob analysisJob = accept(1);
        complete(analysisJob, 0);
        Lease lease = analysisPendingNotificationStore.leaseNext().orElseThrow();
        analysisNotificationState.fallBackToPush(lease);
        analysisNotificationState.createPushNotification(lease);
        analysisNotificationState.createPushNotification(lease);
        analysisPendingNotificationStore.completeAttempt(lease);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE kind='ANALYSIS' AND status='PENDING'", Integer.class)).isEqualTo(1);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isFalse();
        assertThat(notificationScheduled(analysisJob)).isFalse();
    }

    @Test
    void rollsBackInsertedPushWorkIfAcknowledgingTheNotificationFails() {
        AnalysisJob analysisJob = accept(1);
        complete(analysisJob, 0);
        Lease lease = analysisPendingNotificationStore.leaseNext().orElseThrow();
        analysisNotificationState.fallBackToPush(lease);
        jdbcTemplate.execute("CREATE TRIGGER reject_notification_update BEFORE UPDATE ON analysis_job_states FOR EACH ROW BEGIN "
                + "IF JSON_UNQUOTE(JSON_EXTRACT(NEW.job_body,'$.notificationPending')) = 'false' "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'notification-ack-failure'; END IF; END");
        assertThatThrownBy(() -> analysisNotificationState.createPushNotification(lease)).hasStackTraceContaining("notification-ack-failure");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM push_notifications", Integer.class)).isZero();
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isTrue();
        assertThat(notificationScheduled(analysisJob)).isTrue();
    }

    @Test
    void skipsAJobLockedByAnotherInstanceWhenClaimingNotificationWork() throws Exception {
        AnalysisJob firstAnalysisJob = accept(1);
        AnalysisJob secondAnalysisJob = accept(1);
        complete(firstAnalysisJob, 0);
        complete(secondAnalysisJob, 0);
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                analysisJobStateRepository.findForUpdate(firstAnalysisJob.id()).orElseThrow();
                Future<Lease> otherInstanceLease = executorService.submit(() -> analysisPendingNotificationStore.leaseNext().orElseThrow());
                try {
                    assertThat(otherInstanceLease.get(5, TimeUnit.SECONDS).analysisJobId()).isEqualTo(secondAnalysisJob.id());
                } catch (Exception exception) {
                    throw new AssertionError("다른 인스턴스가 잠기지 않은 알림 작업을 선점해야 합니다.", exception);
                }
            });
        }
    }

    @Test
    void locksTheUserBeforeTheJobSoInventoryRegistrationCanKeepTheSameLockOrder() throws Exception {
        AnalysisJob analysisJob = accept(1);
        complete(analysisJob, 0);
        Lease lease = analysisPendingNotificationStore.leaseNext().orElseThrow();
        analysisNotificationState.fallBackToPush(lease);
        CountDownLatch userLockRequested = new CountDownLatch(1);
        AtomicReference<Future<?>> pushCreation = new AtomicReference<>();
        Answer<?> userLockQuery = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer();
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                userRepository.findByIdForUpdate(7L).orElseThrow();
                doAnswer(invocation -> {
                    userLockRequested.countDown();
                    return userLockQuery.answer(invocation);
                }).when(userRepository).findByIdForUpdate(7L);
                pushCreation.set(executorService.submit(() -> analysisNotificationState.createPushNotification(lease)));
                try {
                    assertThat(userLockRequested.await(5, TimeUnit.SECONDS)).isTrue();
                    analysisJobStateRepository.findForUpdate(analysisJob.id()).orElseThrow();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            });
            pushCreation.get().get(5, TimeUnit.SECONDS);
        }
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void rejectsTheOldOwnerAfterAnotherInstanceReclaimsAnExpiredLease() {
        AnalysisJob analysisJob = accept(1);
        complete(analysisJob, 0);
        Lease previousLease = analysisPendingNotificationStore.leaseNext().orElseThrow();
        clock.set(clock.instant().plusSeconds(31).toString());
        Lease renewedLease = analysisPendingNotificationStore.leaseNext().orElseThrow();
        assertThatThrownBy(() -> analysisNotificationState.createPushNotification(previousLease)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> analysisPendingNotificationStore.claimSseAttempt(previousLease)).isInstanceOf(CustomException.class);
        analysisPendingNotificationStore.completeAttempt(previousLease);
        assertThat(analysisJobStateRepository.findById(analysisJob.id()).orElseThrow().getNotificationLeaseToken()).isEqualTo(renewedLease.token());
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isTrue();
    }

    @Test
    void retainsWorkForAnImageThatCompletesWhileAnEarlierNotificationIsBeingHandled() {
        AnalysisJob analysisJob = accept(2);
        complete(analysisJob, 0);
        Lease lease = analysisPendingNotificationStore.leaseNext().orElseThrow();
        analysisNotificationState.recordImageEventPublished(lease, analysisJob.analysisImages().getFirst().requestId());
        complete(analysisJob, 1);
        analysisPendingNotificationStore.completeAttempt(lease);
        assertThat(notificationScheduled(analysisJob)).isTrue();
        assertThat(analysisPendingNotificationStore.leaseNext()).isEmpty();
        clock.set(clock.instant().plus(Duration.ofSeconds(1)).toString());
        assertThat(analysisPendingNotificationStore.leaseNext()).get().extracting(Lease::analysisJobId).isEqualTo(analysisJob.id());
    }

    private AnalysisJob accept(int imageCount) {
        List<AnalysisImage> analysisImages = IntStream.range(0, imageCount)
                .mapToObj(imageIndex -> AnalysisImage.pending("image-" + imageIndex, "ab".repeat(32))).toList();
        return analysisJobAcceptance.accept(7L, 91L, "AUTO", analysisImages);
    }

    private void complete(AnalysisJob analysisJob, int imageIndex) {
        analysisCompletionPersistence.apply(new AnalysisCompletedEvent(UUID.randomUUID().toString(), new AnalysisCompletedEvent.Payload("AnalysisCompleted",
                clock.instant(), analysisJob.analysisImages().get(imageIndex).requestId(), 7L, "ai-" + imageIndex, AnalysisStatus.FAILED, clock.instant(), null,
                new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null))));
    }

    private boolean notificationScheduled(AnalysisJob analysisJob) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT notification_next_attempt_at IS NOT NULL FROM analysis_job_states WHERE analysis_job_id = ?", Boolean.class, analysisJob.id()));
    }
}
