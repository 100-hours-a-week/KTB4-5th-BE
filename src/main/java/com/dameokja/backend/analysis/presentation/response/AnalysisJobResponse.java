package com.dameokja.backend.analysis.presentation.response;

import com.dameokja.backend.analysis.application.AnalysisImageResult;
import com.dameokja.backend.analysis.application.AnalysisJobResult;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record AnalysisJobResponse(String analysisId, AnalysisStatus status, OffsetDateTime submittedAt, OffsetDateTime expiresAt,
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
