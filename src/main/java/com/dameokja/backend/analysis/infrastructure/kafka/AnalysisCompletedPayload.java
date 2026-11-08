package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import java.time.Instant;
import java.time.ZoneOffset;

public record AnalysisCompletedPayload(Instant occurredAt, String requestId, Long userId, Long refrigeratorId, String analysisId,
        AnalysisStatus status, Instant completedAt, AnalysisCompletionSuccess result, AnalysisCompletionFailure error) {
    public AnalysisCompletedPayload {
        if (refrigeratorId != null && refrigeratorId <= 0) {
            throw new IllegalArgumentException("분석 결과의 냉장고 ID가 올바르지 않습니다.");
        }
    }

    public AnalysisCompletion completion() {
        return new AnalysisCompletion(status, completedAt.atOffset(ZoneOffset.UTC), result, error);
    }
}
