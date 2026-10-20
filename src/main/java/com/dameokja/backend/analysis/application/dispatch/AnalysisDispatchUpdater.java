package com.dameokja.backend.analysis.application.dispatch;

import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 모든 갱신은 선점 토큰을 조건으로 한다. 변경 0건은 다른 워커나 콜백이 먼저 상태를 바꿨다는 뜻이다.
@Service
@Transactional
@RequiredArgsConstructor
public class AnalysisDispatchUpdater {
    private final AnalysisDispatchRepository analysisDispatchRepository;
    private final AnalysisDispatchProperties analysisDispatchProperties;
    private final Clock clock;

    public boolean accept(LeasedDispatch leasedDispatch, String aiAnalysisId, boolean alreadyFinished) {
        LocalDateTime now = BusinessTime.now(clock);
        return analysisDispatchRepository.accept(leasedDispatch.id(), leasedDispatch.token(), aiAnalysisId, now, alreadyFinished ? now : now.plus(analysisDispatchProperties.callbackTimeout())) == 1;
    }

    public boolean retryLater(LeasedDispatch leasedDispatch, Duration delay, boolean countsAttempt, AnalysisDispatchErrorCode errorCode) {
        LocalDateTime now = BusinessTime.now(clock);
        return analysisDispatchRepository.reschedule(leasedDispatch.id(), leasedDispatch.token(), now.plus(delay), countsAttempt ? 1 : 0, errorCode.name(), now) == 1;
    }

    public boolean extend(LeasedDispatch leasedDispatch, String aiStatus) {
        LocalDateTime now = BusinessTime.now(clock);
        return analysisDispatchRepository.extend(leasedDispatch.id(), leasedDispatch.token(), aiStatus, now, now.plus(analysisDispatchProperties.callbackTimeout())) == 1;
    }

    public boolean fail(LeasedDispatch leasedDispatch, AnalysisFailureCode failureCode) {
        return fail(leasedDispatch, failureCode.name());
    }

    public boolean fail(LeasedDispatch leasedDispatch, AnalysisDispatchErrorCode errorCode) {
        return fail(leasedDispatch, errorCode.name());
    }

    private boolean fail(LeasedDispatch leasedDispatch, String errorCode) {
        return analysisDispatchRepository.fail(leasedDispatch.id(), leasedDispatch.token(), errorCode, BusinessTime.now(clock)) == 1;
    }

    public void finish(String requestId, String analysisJobId) {
        analysisDispatchRepository.finish(requestId, analysisJobId, BusinessTime.now(clock));
    }
}
