package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Duration;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

// 콜백 기한이 지나면 AI 서버 생존 여부가 아니라 작업 자체를 조회한다. AI가 재시작되면 헬스는 정상이어도 작업은 없다.
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisDispatchChecker {
    private static final String UNREACHABLE = "UNREACHABLE";
    private final AnalysisDispatchRepository repository;
    private final AiAnalysisClient client;
    private final AnalysisDispatchUpdater updater;
    private final AnalysisDispatchFailure failure;
    private final AnalysisCompletionApplier applier;
    private final AnalysisDispatchProperties properties;
    private final Clock clock;

    public void check(LeasedDispatch leased) {
        repository.findLeased(leased.id(), leased.token())
                .filter(dispatch -> dispatch.getStatus() == AnalysisDispatchStatus.CHECKING)
                .ifPresent(dispatch -> check(leased, dispatch));
    }

    private void check(LeasedDispatch leased, AnalysisDispatch dispatch) {
        if (appliedParked(dispatch)) {
            return;
        }
        if (dispatch.isExpired(BusinessTime.now(clock))) {
            failure.fail(leased, dispatch, AnalysisDispatchErrorCode.ANALYSIS_EXPIRED);
            return;
        }
        try {
            String status = client.get(dispatch.getAiAnalysisId()).status();
            // 1단계 AI는 콜백 재전송 수단이 없어 COMPLETED·FAILED여도 콜백 재시도를 기다리고, 끝내 오지 않으면 기한 만료로 실패한다.
            updater.extend(leased, status);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                resend(leased, dispatch);
            } else {
                updater.extend(leased, UNREACHABLE);
            }
        } catch (RestClientException exception) {
            updater.extend(leased, UNREACHABLE);
        }
    }

    // 보관된 콜백 적용이 계속 실패해도 기한 판단은 이어가야 작업이 끝없이 확인 상태에 머물지 않는다.
    private boolean appliedParked(AnalysisDispatch dispatch) {
        try {
            return applier.applyParked(dispatch.getAiAnalysisId());
        } catch (RuntimeException exception) {
            log.warn("보관된 콜백을 적용하지 못했습니다. dispatchId={}", dispatch.getId(), exception);
            return false;
        }
    }

    private void resend(LeasedDispatch leased, AnalysisDispatch dispatch) {
        int attempts = dispatch.getAttemptCount() + 1;
        log.warn("AI에서 작업을 찾지 못해 같은 requestId로 다시 보냅니다. dispatchId={} attempt={}", dispatch.getId(), attempts);
        if (attempts >= properties.maxAttempts()) {
            failure.fail(leased, dispatch, AnalysisDispatchErrorCode.ANALYSIS_UNAVAILABLE);
            return;
        }
        updater.retryLater(leased, Duration.ZERO, true, AnalysisDispatchErrorCode.AI_JOB_LOST);
    }
}
