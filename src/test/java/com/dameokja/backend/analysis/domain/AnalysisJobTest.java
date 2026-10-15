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
}
