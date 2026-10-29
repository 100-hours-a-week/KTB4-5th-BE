package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import java.time.Instant;
import java.time.ZoneOffset;

public record AnalysisCompletedEvent(String messageId, Payload payload) {
    public record Payload(String eventType, Instant occurredAt, String requestId, Long userId, String analysisId,
            AnalysisStatus status, Instant completedAt, AnalysisCompletionSuccess result, AnalysisCompletionFailure error) {
        public AnalysisCompletion completion() {
            return new AnalysisCompletion(status, completedAt.atOffset(ZoneOffset.UTC), result, error);
        }
    }
}
