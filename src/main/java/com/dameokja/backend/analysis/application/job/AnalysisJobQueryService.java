package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobQueryService {
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisOverdueJobExpirer analysisOverdueJobExpirer;

    public AnalysisJobResult get(Long userId, String analysisId) {
        return toResult(analysisOverdueJobExpirer.expireIfOverdue(analysisJobStore.findOwned(analysisId, userId)));
    }

    public List<AnalysisJobResult> list(Long userId) {
        return analysisJobStore.findAllOwned(userId).stream().map(analysisOverdueJobExpirer::expireIfOverdue).map(this::toResult).toList();
    }

    private AnalysisJobResult toResult(AnalysisJob job) {
        List<AnalysisImageResult> results = job.analysisImages().stream().map(AnalysisImageResult::fromStored).toList();
        return new AnalysisJobResult(job, results);
    }
}
