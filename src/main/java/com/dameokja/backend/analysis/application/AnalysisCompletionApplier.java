package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisCallbackParking;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisCompletionApplier {
    private final AnalysisJobStore store;
    private final AnalysisCompletionNotifier notifier;
    private final AnalysisDispatchUpdater dispatches;
    private final AnalysisCallbackParking parking;

    // 같은 사진을 다시 요청한 작업들은 AI 분석 하나를 함께 쓰므로, 결과를 그 작업 모두에 적용한다.
    // 다른 결과가 이미 저장된 작업은 건너뛰고, 모든 작업이 충돌할 때만 충돌로 응답한다.
    public void apply(String analysisId, AnalysisCompletion completion) {
        List<String> jobIds = jobIdsOf(analysisId);
        if (jobIds.isEmpty()) {
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        int conflicts = 0;
        for (String jobId : jobIds) {
            try {
                apply(jobId, analysisId, completion);
            } catch (CustomException exception) {
                if (exception.getExceptionCode() != AnalysisExceptionCode.RESULT_CONFLICT) { throw exception; }
                conflicts++;
            }
        }
        if (conflicts == jobIds.size()) {
            throw new CustomException(AnalysisExceptionCode.RESULT_CONFLICT);
        }
    }

    private void apply(String jobId, String analysisId, AnalysisCompletion completion) {
        AnalysisJob job = complete(jobId, analysisId, completion);
        String requestId = job.requestIdOf(analysisId);
        finishDispatch(requestId, jobId);
        notifier.notifyFinished(job, requestId, completion);
    }

    private List<String> jobIdsOf(String analysisId) {
        try {
            return store.findJobIdsByAiAnalysisId(analysisId);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }

    // 적용에 실패하면 보관을 지우지 않아 다음 기한 확인에서 다시 적용한다.
    public boolean applyParked(String analysisId) {
        Optional<AnalysisCompletion> parked = parking.find(analysisId);
        if (parked.isEmpty()) {
            return false;
        }
        apply(analysisId, parked.get());
        parking.remove(analysisId);
        return true;
    }

    private AnalysisJob complete(String jobId, String analysisId, AnalysisCompletion completion) {
        try {
            return store.complete(jobId, analysisId, completion);
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
    private void finishDispatch(String requestId, String jobId) {
        try {
            dispatches.finish(requestId, jobId);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
