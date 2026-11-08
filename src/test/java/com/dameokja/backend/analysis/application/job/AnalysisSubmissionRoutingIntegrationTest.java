package com.dameokja.backend.analysis.application.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchWorker;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = "analysis.kafka-submission-enabled=true")
class AnalysisSubmissionRoutingIntegrationTest extends ServiceIntegrationTest {
    @Autowired AnalysisJobSubmitService analysisJobSubmitService;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired AnalysisJobQueryService analysisJobQueryService;
    @Autowired AnalysisJobDeleteService analysisJobDeleteService;
    @Autowired AnalysisJobStore analysisJobStore;
    @Autowired ImageUploadMetadataStore imageUploadMetadataStore;
    @MockitoBean AnalysisDispatchWorker analysisDispatchWorker;

    @BeforeEach
    void alignClockWithRedisExpiration() {
        // Redis PEXPIREAT은 실제 서버 시각을 쓰므로 테스트의 결과 보관 만료도 실제 미래로 맞춘다.
        clock.set(Instant.now().toString());
    }

    @AfterEach
    void cleanAnalysisRows() {
        jdbcTemplate.update("DELETE FROM analysis_outbox");
        jdbcTemplate.update("DELETE FROM analysis_job_requests");
        jdbcTemplate.update("DELETE FROM analysis_job_states");
        jdbcTemplate.update("DELETE FROM analysis_dispatches");
    }

    @Test
    void submitsToDatabaseAndCombinesBothStoresInNewestOrder() {
        Fixture owner = ownerFixture("접수자");
        AnalysisJob legacyJob = analysisJobStore.create("legacy", owner.userId(), owner.refrigeratorId(), "AUTO",
                List.of(AnalysisImage.pending("old", "cd".repeat(32))));
        clock.set(clock.instant().plusSeconds(1).toString());
        AnalysisJob databaseJob = submitDatabaseJob(owner);
        assertThat(databaseJob.analysisImages()).hasSize(1);
        assertThat(analysisJobQueryService.get(owner.userId(), databaseJob.id()).status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(analysisJobQueryService.list(owner.userId())).extracting(result -> result.analysisJob().id())
                .containsExactly(databaseJob.id(), legacyJob.id());
        assertStoredCounts(1, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_dispatches", Integer.class)).isZero();
        assertThat(analysisJobStore.find(databaseJob.id())).isEmpty();
        verifyNoInteractions(analysisDispatchWorker);
        Fixture otherOwner = ownerFixture("다른 사용자");
        assertThatThrownBy(() -> analysisJobQueryService.get(otherOwner.userId(), databaseJob.id()))
                .isInstanceOf(CustomException.class).extracting(exception -> ((CustomException) exception).getExceptionCode())
                .isEqualTo(GlobalExceptionCode.FORBIDDEN);
    }

    @Test
    void rejectsUnfinishedDeletionAndDeletesTimedOutJobsFromBothStores() {
        Fixture owner = ownerFixture("만료 사용자");
        AnalysisJob databaseJob = submitDatabaseJob(owner);
        AnalysisJob legacyJob = analysisJobStore.create("legacy", owner.userId(), owner.refrigeratorId(), "AUTO",
                List.of(AnalysisImage.pending("old", "cd".repeat(32))));
        assertThatThrownBy(() -> analysisJobDeleteService.delete(owner.userId(), databaseJob.id()))
                .isInstanceOf(CustomException.class).extracting(exception -> ((CustomException) exception).getExceptionCode())
                .isEqualTo(AnalysisExceptionCode.DELETE_IN_PROGRESS);
        clock.set(clock.instant().plusSeconds(1900).toString());
        analysisJobDeleteService.delete(owner.userId(), databaseJob.id());
        assertStoredCounts(0, 0);
        assertThat(analysisJobQueryService.get(owner.userId(), legacyJob.id()).status()).isEqualTo(AnalysisStatus.FAILED);
        analysisJobDeleteService.delete(owner.userId(), legacyJob.id());
        assertThat(analysisJobStore.find(legacyJob.id())).isEmpty();
    }

    @Test
    void readsAndDeletesThePersistedCompletionForANewDatabaseJob() {
        Fixture owner = ownerFixture("완료 사용자");
        AnalysisJob databaseJob = submitDatabaseJob(owner);
        AnalysisCompletedPayload completionPayload = new AnalysisCompletedPayload(clock.instant(), databaseJob.analysisImages().getFirst().requestId(),
                owner.userId(), owner.refrigeratorId(), "ai-completed", AnalysisStatus.FAILED, clock.instant(), null,
                new AnalysisCompletionFailure("ANALYSIS_UNAVAILABLE", "일시적인 분석 장애", true, null));
        analysisCompletionPersistence.apply(EventEnvelope.create(completionPayload));
        assertThat(analysisJobQueryService.get(owner.userId(), databaseJob.id()).status()).isEqualTo(AnalysisStatus.FAILED);
        analysisJobDeleteService.delete(owner.userId(), databaseJob.id());
        assertStoredCounts(0, 0);
    }

    @Test
    void doesNotRestoreExpiredDatabaseJobsFromRedis() {
        Fixture owner = ownerFixture("보관 만료 사용자");
        AnalysisJob databaseJob = submitDatabaseJob(owner);
        analysisJobStore.create(databaseJob.id(), owner.userId(), owner.refrigeratorId(), "AUTO",
                List.of(AnalysisImage.pending("old", "cd".repeat(32))));
        jdbcTemplate.update("UPDATE analysis_job_states SET expires_at = ? WHERE analysis_job_id = ?",
                BusinessTime.now(clock).minusSeconds(1), databaseJob.id());
        assertThatThrownBy(() -> analysisJobQueryService.get(owner.userId(), databaseJob.id()))
                .isInstanceOf(CustomException.class).extracting(exception -> ((CustomException) exception).getExceptionCode())
                .isEqualTo(AnalysisExceptionCode.NOT_FOUND);
        assertThat(analysisJobQueryService.list(owner.userId())).isEmpty();
        assertThatThrownBy(() -> analysisJobDeleteService.delete(owner.userId(), databaseJob.id()))
                .isInstanceOf(CustomException.class).extracting(exception -> ((CustomException) exception).getExceptionCode())
                .isEqualTo(AnalysisExceptionCode.NOT_FOUND);
        assertThat(analysisJobStore.find(databaseJob.id())).isPresent();
    }

    @Test
    void rejectsTheWholeSubmissionWhenOneImageHasNoValidIssuance() {
        Fixture owner = ownerFixture("검증 사용자");
        imageUploadMetadataStore.save(owner.userId(), ImageUploadPurpose.ANALYSIS, "image", "ab".repeat(32));
        assertThatThrownBy(() -> analysisJobSubmitService.submit(owner.userId(), List.of("image", "unissued"), "AUTO"))
                .isInstanceOf(CustomException.class);
        assertStoredCounts(0, 0);
        verifyNoInteractions(analysisDispatchWorker);
    }

    private void assertStoredCounts(int jobCount, int imageCount) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states", Integer.class)).isEqualTo(jobCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests", Integer.class)).isEqualTo(imageCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox", Integer.class)).isEqualTo(imageCount);
    }

    private AnalysisJob submitDatabaseJob(Fixture owner) {
        imageUploadMetadataStore.save(owner.userId(), ImageUploadPurpose.ANALYSIS, "image", "ab".repeat(32));
        return analysisJobSubmitService.submit(owner.userId(), List.of("image", "image"), "AUTO");
    }
}
