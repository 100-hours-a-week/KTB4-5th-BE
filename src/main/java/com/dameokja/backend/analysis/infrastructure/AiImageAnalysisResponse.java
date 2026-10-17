package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import java.time.OffsetDateTime;

public record AiImageAnalysisResponse(String analysisId, String status, String stage, OffsetDateTime submittedAt,
        OffsetDateTime startedAt, OffsetDateTime completedAt, AiImageAnalysisResult result, Error error) {
    public record Error(String code, String message, boolean retryable, Integer retryAfterMs) {}

    public boolean isFinished() {
        return AnalysisStatus.COMPLETED.name().equals(status) || AnalysisStatus.FAILED.name().equals(status);
    }

    // 콜백을 끝내 받지 못했을 때 조회 결과로 대신 완료하려면 콜백과 같은 형식이어야 한다.
    public AnalysisCompletion toCompletion() {
        if (AnalysisStatus.COMPLETED.name().equals(status)) {
            return new AnalysisCompletion(AnalysisStatus.COMPLETED, completedAt, result.toCompletionResult(), null);
        }
        return new AnalysisCompletion(AnalysisStatus.FAILED, completedAt, null,
                new AnalysisCompletionFailure(error.code(), error.message(), error.retryable(), error.retryAfterMs()));
    }
}
