package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import java.util.List;

public record AnalysisJobResult(AnalysisJob analysisJob, List<AnalysisImageResult> analysisImageResults) {
    public AnalysisStatus status() {
        if (analysisImageResults.stream().allMatch(analysisImageResult -> analysisImageResult.status() == AnalysisStatus.QUEUED)) {
            return AnalysisStatus.QUEUED;
        }
        if (analysisImageResults.stream().anyMatch(analysisImageResult -> analysisImageResult.status().isInProgress())) {
            return AnalysisStatus.PROCESSING;
        }
        if (analysisImageResults.stream().allMatch(analysisImageResult -> analysisImageResult.status() == AnalysisStatus.FAILED)) {
            return AnalysisStatus.FAILED;
        }
        if (analysisImageResults.stream().anyMatch(analysisImageResult -> analysisImageResult.status() == AnalysisStatus.FAILED)) {
            return AnalysisStatus.PARTIALLY_COMPLETED;
        }
        return AnalysisStatus.COMPLETED;
    }
}
