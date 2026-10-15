package com.dameokja.backend.analysis.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalysisCompletionFailure(String code, String message, @JsonProperty(required = true) boolean retryable, Integer retryAfterMs) {}
