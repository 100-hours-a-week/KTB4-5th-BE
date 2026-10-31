package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobQueryService {
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final AnalysisDatabaseJobExpirer analysisDatabaseJobExpirer;

    public AnalysisJobResult get(Long userId, String analysisId) {
        return toResult(analysisDatabaseJobExpirer.expireIfOverdue(analysisJobStateAccess.findOwned(analysisId, userId)));
    }

    public List<AnalysisJobResult> list(Long userId) {
        return analysisJobStateAccess.findAllOwned(userId).stream().map(analysisDatabaseJobExpirer::expireIfOverdue).map(this::toResult).toList();
    }

    private AnalysisJobResult toResult(AnalysisJob job) {
        List<AnalysisImageResult> results = job.analysisImages().stream().map(AnalysisImageResult::fromStored).toList();
        return new AnalysisJobResult(job, results);
    }
}
