package com.dameokja.backend.analysis.application.feedback;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("analysis.feedback")
public record AnalysisFeedbackProperties(@NotNull Duration retryDelay, @NotNull Duration leaseDuration, @NotNull Duration expiresAfter, @Min(1) int batchSize) {
    @AssertTrue(message = "분석 피드백 기간 설정은 1ms 이상이어야 합니다.")
    public boolean isValidDurations() {
        return Stream.of(retryDelay, leaseDuration, expiresAfter).allMatch(duration -> duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0);
    }
}
