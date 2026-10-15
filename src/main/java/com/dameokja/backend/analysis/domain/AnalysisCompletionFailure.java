package com.dameokja.backend.analysis.domain;

public record AnalysisCompletionFailure(String code, String message, boolean retryable, Integer retryAfterMs) {}
