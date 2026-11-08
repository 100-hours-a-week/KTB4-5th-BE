package com.dameokja.backend.analysis.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("analysis.outbox")
public record AnalysisOutboxProperties(@Min(1) int batchSize, @NotNull @DurationMin(seconds = 1) Duration leaseDuration,
        @NotNull @DurationMin(seconds = 1) Duration retryDelay) {}
