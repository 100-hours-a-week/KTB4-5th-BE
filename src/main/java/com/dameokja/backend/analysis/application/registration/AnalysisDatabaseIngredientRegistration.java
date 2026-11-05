package com.dameokja.backend.analysis.application.registration;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackOutbox;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.create.IngredientWriteResult;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnalysisDatabaseIngredientRegistration {
    private final AnalysisJobStateAccess analysisJobStateAccess;
    private final AnalysisFeedbackOutbox analysisFeedbackOutbox;
    private final RefrigeratorAccessService refrigeratorAccessService;

    public boolean exists(String analysisJobId) { return analysisJobStateAccess.exists(analysisJobId); }

    @Transactional
    public IngredientWriteResult create(Long userId, Long refrigeratorId, String analysisJobId, Function<AnalysisJob, IngredientWriteResult> registration) {
        // 재고 쓰기와 같은 잠금 순서를 지키고, 분석 결과 소비까지 같은 트랜잭션에서 처리한다.
        refrigeratorAccessService.lockForWrite(userId, refrigeratorId);
        AnalysisJob job = analysisJobStateAccess.findOwnedForUpdate(analysisJobId, userId);
        if (job.refrigeratorId() != null && !refrigeratorId.equals(job.refrigeratorId())) {
            throw new CustomException(AnalysisExceptionCode.INVALID_REGISTRATION);
        }
        IngredientWriteResult result = registration.apply(job);
        analysisJobStateAccess.deleteOwned(job.id(), userId);
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueFeedback(AnalysisJob job, String analysisId, AiAnalysisFeedbackRequest feedback) {
        analysisFeedbackOutbox.enqueue(job.userId(), job.requestIdOf(analysisId), analysisId, feedback);
    }
}
