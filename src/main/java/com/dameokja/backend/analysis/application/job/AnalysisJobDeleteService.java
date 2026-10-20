package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisJobDeleteService {
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisOverdueJobExpirer analysisOverdueJobExpirer;

    // 종료된 묶음은 콜백으로 다시 진행 상태가 되지 않으므로 확인과 삭제 사이에 잠금을 두지 않는다.
    public void delete(Long userId, String analysisId) {
        AnalysisJob job = analysisOverdueJobExpirer.expireIfOverdue(analysisJobStore.findOwned(analysisId, userId));
        if (!job.finished()) {
            throw new CustomException(AnalysisExceptionCode.DELETE_IN_PROGRESS);
        }
        analysisJobStore.delete(job.id());
    }
}
