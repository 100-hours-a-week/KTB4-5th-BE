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
    @Autowired AnalysisDatabasePendingNotificationStore analysisDatabasePendingNotificationStore;
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisJobStateRepository analysisJobStateRepository;
    @Autowired TransactionTemplate transactionTemplate;
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
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).isEmpty();
        transactionTemplate.executeWithoutResult(status -> {
            complete(job, 0);
            status.setRollbackOnly();
        });
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).isEmpty();
        complete(job, 0);
        transactionTemplate.executeWithoutResult(status -> {
            Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
            assertThat(lease.userId()).isEqualTo(owner.userId());
            assertThat(analysisDatabasePendingNotificationStore.claimSseAttempt(lease)).isEqualTo(1);
            assertThat(analysisDatabasePendingNotificationStore.claimImageEventAttempt(lease, requestId(job, 0))).isTrue();
            status.setRollbackOnly();
        });
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        assertThat(analysisDatabasePendingNotificationStore.claimSseAttempt(lease)).isEqualTo(1);
        assertThat(analysisDatabasePendingNotificationStore.claimImageEventAttempt(lease, requestId(job, 0))).isTrue();
    }

    @Test
    void leasesEachJobOnlyOnceAcrossConcurrentCallers() throws Exception {
        AnalysisJob job = accept(1);
        complete(job, 0);
        List<Object> results = concurrently(List.of(analysisDatabasePendingNotificationStore::leaseNext, analysisDatabasePendingNotificationStore::leaseNext));
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
            transactionTemplate.executeWithoutResult(status -> {
                analysisJobStateRepository.findForUpdate(first.id()).orElseThrow();
                Future<Lease> lease = executor.submit(() -> analysisDatabasePendingNotificationStore.leaseNext().orElseThrow());
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
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        clock.set(clock.instant().plusSeconds(30).toString());
        assertThatThrownBy(() -> analysisDatabasePendingNotificationStore.claimSseAttempt(lease)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> analysisDatabasePendingNotificationStore.claimImageEventAttempt(lease, requestId(job, 0))).isInstanceOf(CustomException.class);
        analysisDatabasePendingNotificationStore.completeAttempt(lease);
        assertThat(stored(job).getNotificationLeaseToken()).isEqualTo(lease.token());
        assertThat(stored(job).getNotificationSseAttemptCount()).isZero();
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).isPresent();
    }

    @Test
    void protectsTheNewOwnerFromAnOldLease() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Lease old = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        clock.set(clock.instant().plusSeconds(30).toString());
        Lease current = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThatThrownBy(() -> analysisDatabasePendingNotificationStore.claimSseAttempt(old)).isInstanceOf(CustomException.class);
        analysisDatabasePendingNotificationStore.completeAttempt(old);
        assertThat(stored(job).getNotificationLeaseToken()).isEqualTo(current.token());
        assertThat(analysisDatabasePendingNotificationStore.claimSseAttempt(current)).isEqualTo(1);
    }

    @Test
    void keepsAttemptLimitsAcrossLeasesAndSeparatelyForEachImage() {
        AnalysisJob job = accept(2);
        complete(job, 0);
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(analysisDatabasePendingNotificationStore.claimSseAttempt(lease)).isEqualTo(attempt);
            assertThat(analysisDatabasePendingNotificationStore.claimImageEventAttempt(lease, requestId(job, 0))).isTrue();
        }
        analysisDatabasePendingNotificationStore.completeAttempt(lease);
        complete(job, 1);
        clock.set(clock.instant().plusSeconds(5).toString());
        Lease next = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        assertThat(analysisDatabasePendingNotificationStore.claimSseAttempt(next)).isZero();
        assertThat(analysisDatabasePendingNotificationStore.claimImageEventAttempt(next, requestId(job, 0))).isFalse();
        assertThat(analysisDatabasePendingNotificationStore.claimImageEventAttempt(next, requestId(job, 1))).isTrue();
        assertThat(stored(job).getNotificationImageAttemptCounts()).containsEntry(requestId(job, 0), 3).containsEntry(requestId(job, 1), 1);
    }

    @Test
    void retainsWorkForAnImageCompletingDuringAnEarlierAttempt() {
        AnalysisJob job = accept(2);
        complete(job, 0);
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        analysisJobStateAccess.update(job.id(), latest -> latest.publishImageEvent(requestId(job, 0)));
        complete(job, 1);
        analysisDatabasePendingNotificationStore.completeAttempt(lease);
        assertThat(stored(job).getNotificationNextAttemptAt()).isEqualTo(BusinessTime.now(clock).plusSeconds(5));
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).isEmpty();
        clock.set(clock.instant().plus(Duration.ofSeconds(5)).toString());
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).get().extracting(Lease::analysisJobId).isEqualTo(job.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSE", "PUSH", "EXHAUSTED"})
    void finishesAcknowledgedWorkWhenImagesArePublishedOrExhaustedOrPushIsSelected(String outcome) {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        if (outcome.equals("EXHAUSTED")) {
            for (int attempt = 0; attempt < 3; attempt++) { analysisDatabasePendingNotificationStore.claimImageEventAttempt(lease, requestId(job, 0)); }
        }
        analysisJobStateAccess.update(job.id(), latest -> {
            AnalysisJob selected = latest.selectNotificationChannel(outcome.equals("PUSH") ? AnalysisNotificationChannel.PUSH : AnalysisNotificationChannel.SSE);
            return (outcome.equals("SSE") ? selected.publishImageEvent(requestId(job, 0)) : selected).acknowledgeNotification();
        });
        analysisDatabasePendingNotificationStore.completeAttempt(lease);
        assertThat(stored(job).getNotificationNextAttemptAt()).isNull();
        assertThat(stored(job).getNotificationLeaseToken()).isNull();
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).isEmpty();
    }

    @Test
    void clearsExpiredResultWorkWithoutRetryingIt() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        clock.set(analysisJobStateAccess.find(job.id()).orElseThrow().expiresAt().toInstant().toString());
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        analysisDatabasePendingNotificationStore.completeAttempt(lease);
        assertThat(stored(job).getNotificationNextAttemptAt()).isNull();
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).isEmpty();
    }

    @Test
    void toleratesAJobConsumedByInventoryRegistration() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Lease lease = analysisDatabasePendingNotificationStore.leaseNext().orElseThrow();
        analysisJobStateAccess.deleteOwned(job.id(), owner.userId());
        analysisDatabasePendingNotificationStore.completeAttempt(lease);
        assertThatThrownBy(() -> analysisDatabasePendingNotificationStore.claimSseAttempt(lease)).isInstanceOf(CustomException.class);
        assertThat(analysisDatabasePendingNotificationStore.leaseNext()).isEmpty();
    }

    private AnalysisJob accept(int imageCount) {
        if (owner == null) { owner = ownerFixture("DB알림"); }
        AnalysisJob job = analysisJobAcceptance.accept(owner.userId(), "AUTO", IntStream.range(0, imageCount)
                .mapToObj(index -> AnalysisImage.pending("image-" + createdJobs.size() + "-" + index, "ab".repeat(32))).toList());
        createdJobs.add(job);
        return job;
    }

    private String requestId(AnalysisJob job, int index) { return job.analysisImages().get(index).requestId(); }

    private void complete(AnalysisJob job, int index) {
        analysisCompletionPersistence.apply(EventEnvelope.create(new AnalysisCompletedPayload(clock.instant(), requestId(job, index), owner.userId(), owner.refrigeratorId(),
                "ai-" + job.id() + "-" + index, AnalysisStatus.FAILED, clock.instant(), null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null))));
    }

    private AnalysisJobState stored(AnalysisJob job) { return analysisJobStateRepository.findById(job.id()).orElseThrow(); }
}
