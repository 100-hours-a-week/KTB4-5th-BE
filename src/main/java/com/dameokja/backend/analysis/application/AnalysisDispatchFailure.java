package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisDispatchFailure {
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisDispatchUpdater analysisDispatchUpdater;
    private final AnalysisCompletionNotifier analysisCompletionNotifier;
    private final Clock clock;

    // 사용자가 보는 Redis 결과를 먼저 실패로 바꾼다. 전달 행을 먼저 끝내면 그 사이 장애에서 결과가 처리 중으로 남는다.
    public void fail(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch, AnalysisFailureCode failureCode) {
        if (analysisJobStore.find(analysisDispatch.getAnalysisJobId()).isEmpty()) {
            analysisDispatchUpdater.fail(leasedDispatch, failureCode);
            return;
        }
        AnalysisCompletion failedCompletion = new AnalysisCompletion(AnalysisStatus.FAILED, BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime(),
                null, failureCode.toFailure());
        AnalysisJob analysisJob = recordFailure(analysisDispatch, failedCompletion);
        if (analysisDispatchUpdater.fail(leasedDispatch, failureCode)) {
            String requestId = analysisDispatch.getRequestId();
            analysisCompletionNotifier.notifyFinished(analysisJob, requestId, completionOf(analysisJob, requestId));
        }
    }

    // 이전 시도에서 이미 실패를 기록했거나 늦은 콜백이 먼저 저장했으면 그 결과를 그대로 둔다.
    private AnalysisJob recordFailure(AnalysisDispatch analysisDispatch, AnalysisCompletion failedCompletion) {
        try {
            return analysisJobStore.fail(analysisDispatch.getAnalysisJobId(), analysisDispatch.getRequestId(), failedCompletion);
        } catch (CustomException exception) {
            if (exception.getExceptionCode() != AnalysisExceptionCode.RESULT_CONFLICT) {
                throw exception;
            }
            return analysisJobStore.find(analysisDispatch.getAnalysisJobId()).orElseThrow(() -> exception);
        }
    }

    private AnalysisCompletion completionOf(AnalysisJob analysisJob, String requestId) {
        return analysisJob.analysisImages().stream().filter(analysisImage -> requestId.equals(analysisImage.requestId())).findFirst().orElseThrow().completion();
    }
}
