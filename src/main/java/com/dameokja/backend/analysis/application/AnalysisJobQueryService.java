package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobQueryService {
    private final AnalysisJobStore analysisJobStore;

    public AnalysisJobResult get(Long userId, String analysisId) {
        AnalysisJob job = analysisJobStore.findOwned(analysisId, userId);
        List<AnalysisImageResult> results = job.analysisImages().stream().map(AnalysisImageResult::fromStored).toList();
        return new AnalysisJobResult(job, results);
    }
}
