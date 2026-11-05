package com.dameokja.backend.analysis.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore.Lease;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisDatabasePendingNotificationStoreTest extends ServiceIntegrationTest {
    @Autowired AnalysisDatabasePendingNotificationStore notifications;
    @Autowired AnalysisJobAcceptance acceptance;
    @Autowired AnalysisCompletionPersistence completions;
    @Autowired AnalysisJobStateAccess states;
    @Autowired AnalysisJobStateRepository jobs;
    @Autowired TransactionTemplate transactions;
    private final List<AnalysisJob> createdJobs = new ArrayList<>();
    private Fixture owner;

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        for (AnalysisJob job : createdJobs) {
            jdbcTemplate.update("DELETE FROM analysis_outbox WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_states WHERE analysis_job_id = ?", job.id());
        }
        createdJobs.clear();
        owner = null;
        super.cleanDatabaseAndResetTime();
    }

    @Test
    void leasesOnlyCommittedCompletionWorkAndRollsBackItsAttemptCounts() {
        AnalysisJob job = accept(1);
        assertThat(notifications.leaseNext()).isEmpty();
        transactions.executeWithoutResult(status -> {
            complete(job, 0);
            status.setRollbackOnly();
        });
        assertThat(notifications.leaseNext()).isEmpty();
        complete(job, 0);
        transactions.executeWithoutResult(status -> {
            Lease lease = notifications.leaseNext().orElseThrow();
            assertThat(lease.userId()).isEqualTo(owner.userId());
            assertThat(notifications.claimSseAttempt(lease)).isEqualTo(1);
            assertThat(notifications.claimImageEventAttempt(lease, requestId(job, 0))).isTrue();
            status.setRollbackOnly();
        });
        Lease lease = notifications.leaseNext().orElseThrow();
        assertThat(notifications.claimSseAttempt(lease)).isEqualTo(1);
        assertThat(notifications.claimImageEventAttempt(lease, requestId(job, 0))).isTrue();
    }

    @Test
    void leasesEachJobOnlyOnceAcrossConcurrentCallers() throws Exception {
        AnalysisJob job = accept(1);
        complete(job, 0);
        List<Object> results = concurrently(List.of(notifications::leaseNext, notifications::leaseNext));
        assertThat(results).containsExactlyInAnyOrder(java.util.Optional.empty(), java.util.Optional.of(
                new Lease(job.id(), stored(job).getNotificationLeaseToken(), owner.userId())));
    }

    @Test
    void skipsAJobLockedByAnotherTransaction() throws Exception {
        AnalysisJob first = accept(1);
        AnalysisJob second = accept(1);
        complete(first, 0);
        complete(second, 0);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            transactions.executeWithoutResult(status -> {
                jobs.findForUpdate(first.id()).orElseThrow();
                Future<Lease> lease = executor.submit(() -> notifications.leaseNext().orElseThrow());
                try {
                    assertThat(lease.get(5, TimeUnit.SECONDS).analysisJobId()).isEqualTo(second.id());
                } catch (Exception exception) {
                    throw new AssertionError("잠기지 않은 알림 작업을 선점해야 합니다.", exception);
                }
            });
        }
    }

    @Test
    void rejectsAnExpiredLeaseEvenBeforeItIsReclaimed() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Lease lease = notifications.leaseNext().orElseThrow();
        clock.set(clock.instant().plusSeconds(30).toString());
        assertThatThrownBy(() -> notifications.claimSseAttempt(lease)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> notifications.claimImageEventAttempt(lease, requestId(job, 0))).isInstanceOf(CustomException.class);
        notifications.completeAttempt(lease);
        assertThat(stored(job).getNotificationLeaseToken()).isEqualTo(lease.token());
        assertThat(stored(job).getNotificationSseAttemptCount()).isZero();
        assertThat(notifications.leaseNext()).isPresent();
    }

    @Test
    void protectsTheNewOwnerFromAnOldLease() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Lease old = notifications.leaseNext().orElseThrow();
        clock.set(clock.instant().plusSeconds(30).toString());
        Lease current = notifications.leaseNext().orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThatThrownBy(() -> notifications.claimSseAttempt(old)).isInstanceOf(CustomException.class);
        notifications.completeAttempt(old);
        assertThat(stored(job).getNotificationLeaseToken()).isEqualTo(current.token());
        assertThat(notifications.claimSseAttempt(current)).isEqualTo(1);
    }

    @Test
    void keepsAttemptLimitsAcrossLeasesAndSeparatelyForEachImage() {
        AnalysisJob job = accept(2);
        complete(job, 0);
        Lease lease = notifications.leaseNext().orElseThrow();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(notifications.claimSseAttempt(lease)).isEqualTo(attempt);
            assertThat(notifications.claimImageEventAttempt(lease, requestId(job, 0))).isTrue();
        }
        notifications.completeAttempt(lease);
        complete(job, 1);
        clock.set(clock.instant().plusSeconds(5).toString());
        Lease next = notifications.leaseNext().orElseThrow();
        assertThat(notifications.claimSseAttempt(next)).isZero();
        assertThat(notifications.claimImageEventAttempt(next, requestId(job, 0))).isFalse();
        assertThat(notifications.claimImageEventAttempt(next, requestId(job, 1))).isTrue();
        assertThat(stored(job).getNotificationImageAttemptCounts()).containsEntry(requestId(job, 0), 3).containsEntry(requestId(job, 1), 1);
    }

    @Test
    void retainsWorkForAnImageCompletingDuringAnEarlierAttempt() {
        AnalysisJob job = accept(2);
        complete(job, 0);
        Lease lease = notifications.leaseNext().orElseThrow();
        states.update(job.id(), latest -> latest.publishImageEvent(requestId(job, 0)));
        complete(job, 1);
        notifications.completeAttempt(lease);
        assertThat(stored(job).getNotificationNextAttemptAt()).isEqualTo(BusinessTime.now(clock).plusSeconds(5));
        assertThat(notifications.leaseNext()).isEmpty();
        clock.set(clock.instant().plus(Duration.ofSeconds(5)).toString());
        assertThat(notifications.leaseNext()).get().extracting(Lease::analysisJobId).isEqualTo(job.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSE", "PUSH", "EXHAUSTED"})
    void finishesAcknowledgedWorkWhenImagesArePublishedOrExhaustedOrPushIsSelected(String outcome) {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Lease lease = notifications.leaseNext().orElseThrow();
        if (outcome.equals("EXHAUSTED")) {
            for (int attempt = 0; attempt < 3; attempt++) { notifications.claimImageEventAttempt(lease, requestId(job, 0)); }
        }
        states.update(job.id(), latest -> {
            AnalysisJob selected = latest.selectNotificationChannel(outcome.equals("PUSH") ? AnalysisNotificationChannel.PUSH : AnalysisNotificationChannel.SSE);
            return (outcome.equals("SSE") ? selected.publishImageEvent(requestId(job, 0)) : selected).acknowledgeNotification();
        });
        notifications.completeAttempt(lease);
        assertThat(stored(job).getNotificationNextAttemptAt()).isNull();
        assertThat(stored(job).getNotificationLeaseToken()).isNull();
        assertThat(notifications.leaseNext()).isEmpty();
    }

    @Test
    void clearsExpiredResultWorkWithoutRetryingIt() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        clock.set(states.find(job.id()).orElseThrow().expiresAt().toInstant().toString());
        Lease lease = notifications.leaseNext().orElseThrow();
        notifications.completeAttempt(lease);
        assertThat(stored(job).getNotificationNextAttemptAt()).isNull();
        assertThat(notifications.leaseNext()).isEmpty();
    }

    @Test
    void toleratesAJobConsumedByInventoryRegistration() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Lease lease = notifications.leaseNext().orElseThrow();
        states.deleteOwned(job.id(), owner.userId());
        notifications.completeAttempt(lease);
        assertThatThrownBy(() -> notifications.claimSseAttempt(lease)).isInstanceOf(CustomException.class);
        assertThat(notifications.leaseNext()).isEmpty();
    }

    private AnalysisJob accept(int imageCount) {
        if (owner == null) { owner = ownerFixture("DB알림"); }
        AnalysisJob job = acceptance.accept(owner.userId(), "AUTO", IntStream.range(0, imageCount)
                .mapToObj(index -> AnalysisImage.pending("image-" + createdJobs.size() + "-" + index, "ab".repeat(32))).toList());
        createdJobs.add(job);
        return job;
    }

    private String requestId(AnalysisJob job, int index) { return job.analysisImages().get(index).requestId(); }

    private void complete(AnalysisJob job, int index) {
        completions.apply(EventEnvelope.create(new AnalysisCompletedPayload(clock.instant(), requestId(job, index), owner.userId(), owner.refrigeratorId(),
                "ai-" + job.id() + "-" + index, AnalysisStatus.FAILED, clock.instant(), null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null))));
    }

    private AnalysisJobState stored(AnalysisJob job) { return jobs.findById(job.id()).orElseThrow(); }
}
