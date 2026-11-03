package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobDeleteService {
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final AnalysisDatabaseJobExpirer analysisDatabaseJobExpirer;

    public void delete(Long userId, String analysisId) {
        AnalysisJob job = analysisDatabaseJobExpirer.expireIfOverdue(analysisJobStateAccess.findOwned(analysisId, userId));
        if (!job.finished()) {
            throw new CustomException(AnalysisExceptionCode.DELETE_IN_PROGRESS);
        }
        analysisJobStateAccess.deleteOwned(job.id(), userId);
    }
}
