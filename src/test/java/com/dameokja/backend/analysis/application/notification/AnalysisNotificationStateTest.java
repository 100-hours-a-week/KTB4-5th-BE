package com.dameokja.backend.analysis.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore.Lease;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisNotificationStateTest extends ServiceIntegrationTest {
    @Autowired AnalysisNotificationState analysisNotificationState;
    @Autowired AnalysisDatabasePendingNotificationStore analysisDatabasePendingNotificationStore;
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired AnalysisJobStateRepository analysisJobStateRepository;
    @Autowired NotificationPreferenceService preferences;
    @Autowired TransactionTemplate transactionTemplate;
    private final List<AnalysisJob> createdJobs = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_push_failure");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_job_failure");
        for (AnalysisJob job : createdJobs) {
            jdbcTemplate.update("DELETE FROM analysis_outbox WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_states WHERE analysis_job_id = ?", job.id());
        }
        for (Long userId : createdUsers) {
            jdbcTemplate.update("DELETE FROM push_notifications WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", userId);
        }
        createdJobs.clear();
        createdUsers.clear();
        super.cleanDatabaseAndResetTime();
    }

    @Test
    void persistsChannelImageHistoryAndAcknowledgesOnlyOnce() {
        AnalysisJob job = completedJob();
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        String requestId = job.analysisImages().getFirst().requestId();
        assertThat(analysisNotificationState.find(job.id())).isPresent();
        assertThat(analysisNotificationState.selectNotificationChannel(lease, AnalysisNotificationChannel.SSE).notificationChannel()).isEqualTo(AnalysisNotificationChannel.SSE);
        analysisNotificationState.recordImageEventPublished(lease, requestId);
        analysisNotificationState.recordImageEventPublished(lease, requestId);
        assertThat(analysisNotificationState.find(job.id()).orElseThrow().publishedImageEvents()).containsExactly(requestId);
        assertThat(analysisNotificationState.acknowledgeNotification(lease)).isTrue();
        assertThat(analysisNotificationState.acknowledgeNotification(lease)).isFalse();
        assertThat(analysisNotificationState.find(job.id()).orElseThrow().notificationPending()).isFalse();
    }

    @Test
    void createsPushAndAcknowledgesOnce() {
        AnalysisJob job = completedJob();
        enablePush(job.userId());
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        analysisNotificationState.selectNotificationChannel(lease, AnalysisNotificationChannel.SSE);
        analysisNotificationState.fallBackToPush(lease);
        analysisNotificationState.createPushNotification(lease);
        analysisNotificationState.createPushNotification(lease);
        assertThat(pushCount(job.userId())).isEqualTo(1);
        AnalysisJob result = analysisNotificationState.find(job.id()).orElseThrow();
        assertThat(result.notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
        assertThat(result.notificationPending()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "job"})
    void rollsBackPushAndAcknowledgementWhenEitherWriteFails(String failure) {
        AnalysisJob job = completedJob();
        enablePush(job.userId());
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        analysisNotificationState.fallBackToPush(lease);
        if (failure.equals("push")) {
            jdbcTemplate.execute("CREATE TRIGGER test_push_failure BEFORE INSERT ON push_notifications FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'push failure'");
        } else {
            jdbcTemplate.execute("CREATE TRIGGER test_job_failure BEFORE UPDATE ON analysis_job_states FOR EACH ROW "
                    + "IF JSON_EXTRACT(NEW.job_body, '$.notificationPending') = CAST('false' AS JSON) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job failure'; END IF");
        }
        assertThatThrownBy(() -> analysisNotificationState.createPushNotification(lease)).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining(failure + " failure");
        assertThat(pushCount(job.userId())).isZero();
        assertThat(analysisNotificationState.find(job.id()).orElseThrow().notificationPending()).isTrue();
    }

    @Test
    void rejectsExpiredReclaimedAndWrongOwnerLeasesForEveryWrite() {
        AnalysisJob job = completedJob();
        Lease old = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        Lease wrongOwner = new Lease(old.analysisJobId(), old.token(), old.userId() + 1);
        assertWritesRejected(wrongOwner, job);
        clock.set(clock.instant().plusSeconds(30).toString());
        assertWritesRejected(old, job);
        Lease current = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertWritesRejected(old, job);
        assertThat(analysisNotificationState.selectNotificationChannel(current, AnalysisNotificationChannel.SSE).notificationChannel()).isEqualTo(AnalysisNotificationChannel.SSE);
    }

    @Test
    void locksUserBeforeJobWhenCreatingPush() throws Exception {
        AnalysisJob job = completedJob();
        enablePush(job.userId());
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        analysisNotificationState.fallBackToPush(lease);
        CountDownLatch userLockAttempted = new CountDownLatch(1);
        Answer<?> delegate = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> worker = transactionTemplate.execute(status -> {
                userRepository.findByIdForUpdate(job.userId()).orElseThrow();
                doAnswer(invocation -> {
                    userLockAttempted.countDown();
                    return delegate.answer(invocation);
                }).when(userRepository).findByIdForUpdate(job.userId());
                Future<?> submitted = executor.submit(() -> analysisNotificationState.createPushNotification(lease));
                try {
                    assertThat(userLockAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                    analysisJobStateRepository.findForUpdate(job.id()).orElseThrow();
                    assertThat(submitted.isDone()).isFalse();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return submitted;
            });
            worker.get(10, TimeUnit.SECONDS);
        }
        assertThat(pushCount(job.userId())).isEqualTo(1);
        assertThat(analysisNotificationState.find(job.id()).orElseThrow().notificationPending()).isFalse();
    }

    private void assertWritesRejected(Lease lease, AnalysisJob job) {
        String requestId = job.analysisImages().getFirst().requestId();
        assertThatThrownBy(() -> analysisNotificationState.selectNotificationChannel(lease, AnalysisNotificationChannel.SSE)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> analysisNotificationState.fallBackToPush(lease)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> analysisNotificationState.recordImageEventPublished(lease, requestId)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> analysisNotificationState.acknowledgeNotification(lease)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> analysisNotificationState.createPushNotification(lease)).isInstanceOf(CustomException.class);
        assertThat(analysisNotificationState.find(job.id()).orElseThrow().notificationPending()).isTrue();
    }

    private AnalysisJob completedJob() {
        Fixture owner = ownerFixture("알림상태");
        createdUsers.add(owner.userId());
        AnalysisJob job = analysisJobAcceptance.accept(owner.userId(), "AUTO", List.of(AnalysisImage.pending("image", "ab".repeat(32))));
        createdJobs.add(job);
        String requestId = job.analysisImages().getFirst().requestId();
        analysisCompletionPersistence.apply(EventEnvelope.create(new AnalysisCompletedPayload(clock.instant(), requestId, owner.userId(), owner.refrigeratorId(),
                "ai-" + job.id(), AnalysisStatus.FAILED, clock.instant(), null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null))));
        return job;
    }

    private void enablePush(Long userId) {
        preferences.create(userRepository.findById(userId).orElseThrow(), true);
        jdbcTemplate.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,vapid_key_version,created_at,updated_at,user_id) "
                + "VALUES (?,?,?,'v1','v1',NOW(6),NOW(6),?)", "https://push.example.com/" + userId, "key", "secret", userId);
    }

    private int pushCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE user_id = ?", Integer.class, userId);
    }
}
