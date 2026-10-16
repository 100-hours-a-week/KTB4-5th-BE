package com.dameokja.backend.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
}
