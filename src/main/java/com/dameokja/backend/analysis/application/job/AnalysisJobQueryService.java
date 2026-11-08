package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobQueryService {
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisOverdueJobExpirer analysisOverdueJobExpirer;
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final AnalysisDatabaseJobExpirer analysisDatabaseJobExpirer;

    public AnalysisJobResult get(Long userId, String analysisId) {
        AnalysisJob analysisJob = analysisJobStateAccess.exists(analysisId)
                ? analysisDatabaseJobExpirer.expireIfOverdue(analysisJobStateAccess.findOwned(analysisId, userId))
                : analysisOverdueJobExpirer.expireIfOverdue(analysisJobStore.findOwned(analysisId, userId));
        return toResult(analysisJob);
    }

    public List<AnalysisJobResult> list(Long userId) {
        Stream<AnalysisJob> databaseJobs = analysisJobStateAccess.findAllOwned(userId).stream().map(analysisDatabaseJobExpirer::expireIfOverdue);
        List<AnalysisJob> legacyJobs = analysisJobStore.findAllOwned(userId);
        Set<String> databaseIds = analysisJobStateAccess.findExistingIds(legacyJobs.stream().map(AnalysisJob::id).toList());
        Stream<AnalysisJob> redisJobs = legacyJobs.stream().filter(analysisJob -> !databaseIds.contains(analysisJob.id())).map(analysisOverdueJobExpirer::expireIfOverdue);
        Comparator<AnalysisJob> newestFirst = Comparator.comparing((AnalysisJob analysisJob) -> analysisJob.submittedAt().toInstant())
                .thenComparing(AnalysisJob::id).reversed();
        return Stream.concat(databaseJobs, redisJobs).sorted(newestFirst).map(this::toResult).toList();
    }

    private AnalysisJobResult toResult(AnalysisJob analysisJob) {
        List<AnalysisImageResult> results = analysisJob.analysisImages().stream().map(AnalysisImageResult::fromStored).toList();
        return new AnalysisJobResult(analysisJob, results);
    }
}
