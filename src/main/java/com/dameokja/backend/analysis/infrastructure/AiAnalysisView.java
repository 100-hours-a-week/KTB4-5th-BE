package com.dameokja.backend.analysis.infrastructure;

import java.time.OffsetDateTime;

public record AiAnalysisView(String analysisId, String status, String stage, OffsetDateTime submittedAt,
        OffsetDateTime startedAt, OffsetDateTime completedAt, AiAnalysisResult result, Error error) {
    public record Error(String code, String message, boolean retryable, Integer retryAfterMs) {}
}
