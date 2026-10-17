package com.dameokja.backend.analysis.application;

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
    private final AnalysisDispatchRepository repository;
    private final AnalysisDispatchProperties properties;
    private final Clock clock;

    public boolean accept(LeasedDispatch leased, String aiAnalysisId) {
        LocalDateTime now = BusinessTime.now(clock);
        return repository.accept(leased.id(), leased.token(), aiAnalysisId, now, now.plus(properties.callbackTimeout())) == 1;
    }

    public boolean retryLater(LeasedDispatch leased, Duration delay, boolean countsAttempt, AnalysisDispatchErrorCode errorCode) {
        LocalDateTime now = BusinessTime.now(clock);
        return repository.reschedule(leased.id(), leased.token(), now.plus(delay), countsAttempt ? 1 : 0, errorCode.name(), now) == 1;
    }

    public boolean extend(LeasedDispatch leased, String aiStatus) {
        LocalDateTime now = BusinessTime.now(clock);
        return repository.extend(leased.id(), leased.token(), aiStatus, now, now.plus(properties.callbackTimeout())) == 1;
    }

    public boolean fail(LeasedDispatch leased, AnalysisFailureCode failureCode) {
        return fail(leased, failureCode.name());
    }

    public boolean fail(LeasedDispatch leased, AnalysisDispatchErrorCode errorCode) {
        return fail(leased, errorCode.name());
    }

    private boolean fail(LeasedDispatch leased, String errorCode) {
        return repository.fail(leased.id(), leased.token(), errorCode, BusinessTime.now(clock)) == 1;
    }

    public void finish(String requestId) {
        repository.finish(requestId, BusinessTime.now(clock));
    }
}
