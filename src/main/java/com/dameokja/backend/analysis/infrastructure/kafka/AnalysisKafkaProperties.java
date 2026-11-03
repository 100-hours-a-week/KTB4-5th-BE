package com.dameokja.backend.analysis.infrastructure.kafka;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("analysis.kafka")
public record AnalysisKafkaProperties(@NotBlank String requestTopic) {}
