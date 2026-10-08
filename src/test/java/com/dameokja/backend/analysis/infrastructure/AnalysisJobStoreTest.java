package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.global.exception.CustomException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisJobStoreTest {
    private final Clock clock = mock(Clock.class);
    private final Instant instant = Instant.parse("2026-10-07T01:00:00Z");
    private final Duration duration = Duration.ofMinutes(1);

    @Test
    void recordsAcceptanceByRequestWithoutOverwritingOtherImages() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(image("first"), image("second")));
        String requestId = analysisJob.analysisImages().getFirst().requestId();
        analysisJobStore.register(analysisJob.id(), 7L, requestId, "ai-first");
        analysisJobStore.register(analysisJob.id(), 7L, requestId, "ai-first");
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsExactly("ai-first", null);
        assertThatThrownBy(() -> analysisJobStore.register(analysisJob.id(), 7L, requestId, "different-ai-id")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analysisJobStore.register(analysisJob.id(), 7L, "unknown-request", "ai-id")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOtherUsersWithoutChangingTheirJob() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(image("first")));
        assertThatThrownBy(() -> analysisJobStore.findOwned(analysisJob.id(), 8L)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("GLOBAL-403-001");
        assertThatThrownBy(() -> analysisJobStore.register(analysisJob.id(), 8L, analysisJob.analysisImages().getFirst().requestId(), "ai-id")).isInstanceOf(CustomException.class);
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().aiAnalysisId()).isNull();
    }

    @Test
    void rejectsExpiredJobsAtTheBoundaryEvenAfterAnUpdate() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(image("first")));
        when(clock.instant()).thenReturn(instant.plusSeconds(59));
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-id");
        when(clock.instant()).thenReturn(instant.plus(duration));
        assertThatThrownBy(() -> analysisJobStore.findOwned(analysisJob.id(), 7L)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-404-001");
        assertThatThrownBy(() -> analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-id")).isInstanceOf(CustomException.class);
    }

    private AnalysisJobStore createAnalysisJobStore() {
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(com.dameokja.backend.global.util.BusinessTime.ZONE);
        when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenReturn(clock);
        return new AnalysisJobStore(clock, duration);
    }

    private AnalysisImage image(String objectKey) { return AnalysisImage.pending(objectKey, "ab".repeat(32)); }
}
