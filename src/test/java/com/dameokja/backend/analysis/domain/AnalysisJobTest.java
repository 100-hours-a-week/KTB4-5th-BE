package com.dameokja.backend.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisJobTest {
    @Test
    void acknowledgesNotificationWithoutChangingTheRestOfTheJob() {
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
        AnalysisJob job = new AnalysisJob("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))),
                submittedAt, submittedAt.plusDays(7), true);

        AnalysisJob acknowledged = job.acknowledgeNotification();

        assertThat(acknowledged.notificationPending()).isFalse();
        assertThat(acknowledged).usingRecursiveComparison().ignoringFields("notificationPending").isEqualTo(job);
    }

    @Test
    void finishesOnlyAfterEveryImageHasAResult() {
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
        AnalysisCompletion failure = new AnalysisCompletion(AnalysisStatus.FAILED, submittedAt, null,
                new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, 1000));
        AnalysisJob job = new AnalysisJob("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)),
                AnalysisImage.pending("second", "cd".repeat(32))), submittedAt, null, false);
        AnalysisJob registered = job.register(job.analysisImages().getFirst().requestId(), "ai-first")
                .register(job.analysisImages().getLast().requestId(), "ai-second");
        AnalysisJob partlyFinished = registered.complete("ai-first", failure, submittedAt.plusDays(7));

        assertThat(job.finished()).isFalse();
        assertThat(partlyFinished.finished()).isFalse();
        assertThat(partlyFinished.complete("ai-second", failure, submittedAt.plusDays(7)).finished()).isTrue();
    }

    @Test
    void replacesTheAiIdOnlyWhileTheImageHasNoResult() {
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
        AnalysisJob job = new AnalysisJob("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))), submittedAt, null, false);
        String requestId = job.analysisImages().getFirst().requestId();
        AnalysisJob resent = job.register(requestId, "ai-lost").register(requestId, "ai-new");
        AnalysisJob finished = resent.complete("ai-new", failure(submittedAt), submittedAt.plusDays(7));

        assertThat(resent.analysisImages().getFirst().aiAnalysisId()).isEqualTo("ai-new");
        assertThat(resent.requestIdOf("ai-new")).isEqualTo(requestId);
        assertThat(finished.register(requestId, "ai-new")).isEqualTo(finished);
        assertThatThrownBy(() -> finished.register(requestId, "ai-other")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsAnImageThatAiNeverAcceptedAndStartsRetentionWhenItIsTheLast() {
        OffsetDateTime submittedAt = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");
        AnalysisJob job = new AnalysisJob("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))), submittedAt, null, false);
        String requestId = job.analysisImages().getFirst().requestId();
        AnalysisJob failed = job.fail(requestId, failure(submittedAt), submittedAt.plusDays(7));

        assertThat(failed.finished()).isTrue();
        assertThat(failed.expiresAt()).isEqualTo(submittedAt.plusDays(7));
        assertThat(failed.notificationPending()).isTrue();
        assertThat(failed.fail(requestId, failure(submittedAt), submittedAt.plusDays(8))).isEqualTo(failed);
        assertThatThrownBy(() -> failed.fail(requestId, failure(submittedAt.plusMinutes(1)), submittedAt.plusDays(7)))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> job.fail("unknown", failure(submittedAt), submittedAt.plusDays(7))).isInstanceOf(IllegalArgumentException.class);
    }

    private AnalysisCompletion failure(OffsetDateTime completedAt) {
        return new AnalysisCompletion(AnalysisStatus.FAILED, completedAt, null,
                new AnalysisCompletionFailure("ANALYSIS_EXPIRED", "실패", true, null));
    }
}
