package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobQueryService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final AnalysisDatabaseJobExpirer analysisDatabaseJobExpirer;

    public AnalysisJobResult get(Long userId, String analysisId) {
        Long refrigeratorId = refrigeratorAccessService.findCurrentForRead(userId).getId();
        return toResult(analysisDatabaseJobExpirer.expireIfOverdue(analysisJobStateAccess.findOwnedInRefrigerator(analysisId, userId, refrigeratorId)));
    }

    public List<AnalysisJobResult> list(Long userId) {
        Long refrigeratorId = refrigeratorAccessService.findCurrentForRead(userId).getId();
        return analysisJobStateAccess.findAllOwnedInRefrigerator(userId, refrigeratorId).stream().map(analysisDatabaseJobExpirer::expireIfOverdue).map(this::toResult).toList();
    }

    private AnalysisJobResult toResult(AnalysisJob job) {
        List<AnalysisImageResult> results = job.analysisImages().stream().map(AnalysisImageResult::fromStored).toList();
        return new AnalysisJobResult(job, results);
    }
}
