package com.dameokja.backend.analysis.infrastructure;

import java.time.OffsetDateTime;

public record AiImageAnalysisResponse(String analysisId, String status, String stage, OffsetDateTime submittedAt,
        OffsetDateTime startedAt, OffsetDateTime completedAt, AiImageAnalysisResult result, Error error) {
    public record Error(String code, String message, boolean retryable, Integer retryAfterMs) {}
}
