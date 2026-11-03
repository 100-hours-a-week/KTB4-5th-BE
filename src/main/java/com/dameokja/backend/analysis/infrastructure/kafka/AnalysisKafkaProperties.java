package com.dameokja.backend.analysis.infrastructure.kafka;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("analysis.kafka")
public record AnalysisKafkaProperties(@NotBlank String requestTopic, @NotBlank String completionTopic, @NotBlank String completionDltTopic,
        @NotNull @DurationMin(seconds = 1) Duration completionRetryDelay, @NotBlank String feedbackTopic, @NotBlank String completionGroupId) {}
