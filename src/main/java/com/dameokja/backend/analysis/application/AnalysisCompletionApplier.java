package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisCompletionApplier {
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisCompletionNotifier analysisCompletionNotifier;
    private final AnalysisDispatchUpdater analysisDispatchUpdater;

    public void apply(String analysisId, AnalysisCompletion completion) {
        AnalysisJob analysisJob = complete(analysisId, completion);
        String requestId = analysisJob.requestIdOf(analysisId);
        finishDispatch(requestId);
        analysisCompletionNotifier.notifyFinished(analysisJob, requestId, completion);
    }

    private AnalysisJob complete(String analysisId, AnalysisCompletion completion) {
        try {
            return analysisJobStore.complete(analysisId, completion);
        } catch (CustomException exception) {
            if (exception.getExceptionCode() == AnalysisExceptionCode.RESULT_CONFLICT) {
                throw exception;
            }
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }

    // 결과 저장 뒤 전달 완료 기록이 실패하면 503으로 응답해 AI 재전송으로 다시 기록한다. 같은 결과는 멱등으로 수락된다.
    private void finishDispatch(String requestId) {
        try {
            analysisDispatchUpdater.finish(requestId);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
