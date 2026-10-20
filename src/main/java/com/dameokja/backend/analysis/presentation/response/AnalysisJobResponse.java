package com.dameokja.backend.analysis.presentation.response;

import com.dameokja.backend.analysis.application.job.AnalysisImageResult;
import com.dameokja.backend.analysis.application.job.AnalysisJobResult;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record AnalysisJobResponse(String analysisId, AnalysisStatus status, OffsetDateTime submittedAt,
        @Schema(description = "묶음 종료 결과 저장 전 null. 전체 종료를 확정해 저장한 시점부터 기본 7일 후", nullable = true) OffsetDateTime expiresAt,
        Integer pollAfterMs, List<AnalysisImageResult> results, AnalysisImageResult.Error error) {
    private static final int POLL_AFTER_MS = 1000;

    public static AnalysisJobResponse from(AnalysisJobResult analysisJobResult) {
        AnalysisJob analysisJob = analysisJobResult.analysisJob();
        AnalysisStatus analysisStatus = analysisJobResult.status();
        Integer pollAfterMs = null;
        if (analysisStatus.isInProgress()) {
            pollAfterMs = POLL_AFTER_MS;
        }
        return new AnalysisJobResponse(analysisJob.id(), analysisStatus, analysisJob.submittedAt(), analysisJob.expiresAt(), pollAfterMs,
                analysisJobResult.analysisImageResults(), null);
    }
}
