package com.dameokja.backend.analysis.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("analysis.dispatch")
public record AnalysisDispatchProperties(@NotBlank String aiTarget, @Min(1) int slots, @NotNull Duration leaseDuration,
        @NotNull Duration callbackTimeout, @NotNull Duration expiresAfter, @Min(1) int maxAttempts,
        @NotNull Duration retryDelay, @NotNull Duration orphanGrace, @Min(1) int checkBatchSize) {
    @AssertTrue(message = "분석 전달 기간 설정은 1ms 이상이어야 합니다.")
    public boolean isValidDurations() {
        return Stream.of(leaseDuration, callbackTimeout, expiresAfter, retryDelay, orphanGrace)
                .allMatch(duration -> duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0);
    }

    public Duration retryDelay(int attemptCount) {
        return retryDelay.multipliedBy(1L << Math.min(Math.max(attemptCount - 1, 0), 6));
    }
}
