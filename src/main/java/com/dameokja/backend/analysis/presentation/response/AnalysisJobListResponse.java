package com.dameokja.backend.analysis.presentation.response;

import com.dameokja.backend.analysis.application.AnalysisJobResult;
import java.util.List;

public record AnalysisJobListResponse(List<AnalysisJobSummaryResponse> analyses) {
    public static AnalysisJobListResponse from(List<AnalysisJobResult> analysisJobResults) {
        return new AnalysisJobListResponse(analysisJobResults.stream().map(AnalysisJobSummaryResponse::from).toList());
    }
}
