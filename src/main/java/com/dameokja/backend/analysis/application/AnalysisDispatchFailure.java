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
    private final AnalysisJobStore store;
    private final AnalysisDispatchUpdater updater;
    private final AnalysisCompletionNotifier notifier;
    private final Clock clock;

    // 사용자가 보는 Redis 결과를 먼저 실패로 바꾼다. 전달 행을 먼저 끝내면 그 사이 장애에서 결과가 처리 중으로 남는다.
    public void fail(LeasedDispatch leased, AnalysisDispatch dispatch, AnalysisFailureCode failureCode) {
        if (store.find(dispatch.getAnalysisJobId()).isEmpty()) {
            updater.fail(leased, failureCode);
            return;
        }
        AnalysisCompletion failure = new AnalysisCompletion(AnalysisStatus.FAILED, BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime(),
                null, failureCode.toFailure());
        AnalysisJob job = recordFailure(dispatch, failure);
        if (updater.fail(leased, failureCode)) {
            String requestId = dispatch.getRequestId();
            notifier.notifyFinished(job, requestId, completionOf(job, requestId));
        }
    }

    // 이전 시도에서 이미 실패를 기록했거나 늦은 콜백이 먼저 저장했으면 그 결과를 그대로 둔다.
    private AnalysisJob recordFailure(AnalysisDispatch dispatch, AnalysisCompletion failure) {
        try {
            return store.fail(dispatch.getAnalysisJobId(), dispatch.getRequestId(), failure);
        } catch (CustomException exception) {
            if (exception.getExceptionCode() != AnalysisExceptionCode.RESULT_CONFLICT) {
                throw exception;
            }
            return store.find(dispatch.getAnalysisJobId()).orElseThrow(() -> exception);
        }
    }

    private AnalysisCompletion completionOf(AnalysisJob job, String requestId) {
        return job.analysisImages().stream().filter(image -> requestId.equals(image.requestId())).findFirst().orElseThrow().completion();
    }
}
