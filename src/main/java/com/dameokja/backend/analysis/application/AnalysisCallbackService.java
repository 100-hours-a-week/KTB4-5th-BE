package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisCallbackService {
    private final AnalysisJobStore store;
    private final AnalysisCompletionValidator validator;
    private final AnalysisCompletionNotifier notifier;
    private final AnalysisDispatchUpdater dispatches;
    private final AnalysisDispatchWorker worker;

    public void receive(String analysisId, AnalysisCompletion completion) {
        try {
            validator.validate(completion);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        AnalysisJob job = complete(analysisId, completion);
        String requestId = job.requestIdOf(analysisId);
        finishDispatch(requestId);
        notifier.notifyFinished(job, requestId, completion);
        worker.wakeUp();
    }

    private AnalysisJob complete(String analysisId, AnalysisCompletion completion) {
        try {
            return store.complete(analysisId, completion);
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
            dispatches.finish(requestId);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
