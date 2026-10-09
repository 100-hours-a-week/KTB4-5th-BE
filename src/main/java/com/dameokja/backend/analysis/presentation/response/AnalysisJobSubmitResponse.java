package com.dameokja.backend.analysis.presentation.response;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record AnalysisJobSubmitResponse(
        @Schema(description = "여러 이미지를 묶은 백엔드 작업 ID") String analysisId,
        AnalysisStatus status,
        OffsetDateTime submittedAt,
        OffsetDateTime expiresAt,
        int pollAfterMs
) {
    private static final int POLL_AFTER_MS = 1000;

    public static AnalysisJobSubmitResponse from(AnalysisJob analysisJob) {
        return new AnalysisJobSubmitResponse(analysisJob.id(), AnalysisStatus.QUEUED, analysisJob.submittedAt(), analysisJob.expiresAt(), POLL_AFTER_MS);
    }
}
