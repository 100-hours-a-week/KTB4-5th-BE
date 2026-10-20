package com.dameokja.backend.analysis.application.dispatch;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.test.util.ReflectionTestUtils;

public final class AnalysisDispatchFixture {
    // 서울 2026-10-13 08:00
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC);
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 8, 0);
    static final LeasedDispatch LEASED = new LeasedDispatch(1L, "token");
    public static final AnalysisDispatchProperties PROPERTIES = new AnalysisDispatchProperties(1, Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofMinutes(1), 3, Duration.ofSeconds(1), Duration.ofSeconds(30), 20);

    private AnalysisDispatchFixture() {}

    static AnalysisDispatch dispatch(AnalysisDispatchStatus status, int attempts, LocalDateTime createdAt, LocalDateTime expiresAt) {
        AnalysisDispatch analysisDispatch = AnalysisDispatch.pending("job-1", 7L, "AUTO", AnalysisImage.pending("first", "ab".repeat(32)), createdAt, expiresAt);
        ReflectionTestUtils.setField(analysisDispatch, "id", LEASED.id());
        ReflectionTestUtils.setField(analysisDispatch, "status", status);
        ReflectionTestUtils.setField(analysisDispatch, "attemptCount", attempts);
        ReflectionTestUtils.setField(analysisDispatch, "leaseToken", LEASED.token());
        ReflectionTestUtils.setField(analysisDispatch, "aiAnalysisId", status == AnalysisDispatchStatus.CHECKING ? "ai-1" : null);
        ReflectionTestUtils.setField(analysisDispatch, "createdAt", createdAt);
        return analysisDispatch;
    }

    static AnalysisDispatch dispatch(AnalysisDispatchStatus status, int attempts) {
        return dispatch(status, attempts, NOW, NOW.plusMinutes(1));
    }
}
