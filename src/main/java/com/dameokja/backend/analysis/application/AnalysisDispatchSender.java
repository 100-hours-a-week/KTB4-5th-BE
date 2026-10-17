package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisSubmitResponse;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisDispatchSender {
    private static final Set<Integer> REJECTED_STATUSES = Set.of(400, 409, 422);
    private static final Set<String> FINISHED_STATUSES = Set.of("COMPLETED", "FAILED");
    private final AnalysisDispatchRepository repository;
    private final AnalysisJobStore store;
    private final AiAnalysisClient client;
    private final AnalysisDispatchUpdater updater;
    private final AnalysisDispatchFailure failure;
    private final AnalysisCompletionApplier applier;
    private final AnalysisDispatchProperties properties;
    private final Clock clock;

    public void send(LeasedDispatch leased) {
        repository.findLeased(leased.id(), leased.token())
                .filter(dispatch -> dispatch.getStatus() == AnalysisDispatchStatus.DISPATCHING)
                .ifPresent(dispatch -> send(leased, dispatch));
    }

    private void send(LeasedDispatch leased, AnalysisDispatch dispatch) {
        LocalDateTime now = BusinessTime.now(clock);
        if (dispatch.isExpired(now)) {
            failure.fail(leased, dispatch, AnalysisFailureCode.ANALYSIS_EXPIRED);
        } else if (store.find(dispatch.getAnalysisJobId()).isPresent()) {
            submit(leased, dispatch);
        } else if (!now.isBefore(dispatch.getCreatedAt().plus(properties.orphanGrace()))) {
            updater.fail(leased, AnalysisDispatchErrorCode.JOB_MISSING);
        } else {
            // 접수 API가 큐 저장 직후 Redis 작업을 만드는 중일 수 있다.
            updater.retryLater(leased, properties.retryDelay(), false, AnalysisDispatchErrorCode.JOB_NOT_READY);
        }
    }

    private void submit(LeasedDispatch leased, AnalysisDispatch dispatch) {
        AiImageAnalysisSubmitResponse response;
        try {
            response = client.submit(request(dispatch));
        } catch (RestClientResponseException exception) {
            handleResponseFailure(leased, dispatch, exception);
            return;
        } catch (RestClientException exception) {
            retryOrFail(leased, dispatch, AnalysisDispatchErrorCode.AI_UNAVAILABLE, exception);
            return;
        }
        String analysisId = response.analysisId();
        if (analysisId == null || analysisId.isBlank()) {
            retryOrFail(leased, dispatch, AnalysisDispatchErrorCode.AI_UNAVAILABLE, null);
            return;
        }
        register(leased, dispatch, analysisId, FINISHED_STATUSES.contains(response.status()));
    }

    // Redis에 AI ID를 먼저 등록해야 빠르게 도착한 콜백이 묶음을 찾는다.
    // 같은 사진의 재요청은 AI가 끝낸 분석을 그대로 돌려주고 콜백을 다시 보내지 않으므로, 기한을 지금으로 두어 바로 조회한다.
    private void register(LeasedDispatch leased, AnalysisDispatch dispatch, String analysisId, boolean alreadyFinished) {
        try {
            store.register(dispatch.getAnalysisJobId(), dispatch.getUserId(), dispatch.getRequestId(), analysisId);
        } catch (RuntimeException exception) {
            log.warn("AI 접수 ID를 등록하지 못해 같은 requestId로 다시 보냅니다. dispatchId={}", dispatch.getId(), exception);
            updater.retryLater(leased, properties.retryDelay(), false, AnalysisDispatchErrorCode.REGISTER_FAILED);
            return;
        }
        updater.accept(leased, analysisId, alreadyFinished);
        applyParked(dispatch, analysisId);
    }

    // 첫 접수 응답이 유실된 사이 도착해 보관된 콜백이 있으면 지금 적용한다. 실패하면 기한 확인에서 다시 적용한다.
    private void applyParked(AnalysisDispatch dispatch, String analysisId) {
        try {
            if (applier.applyParked(analysisId)) {
                log.info("등록 전에 도착한 콜백을 적용했습니다. dispatchId={} analysisId={}", dispatch.getId(), analysisId);
            }
        } catch (RuntimeException exception) {
            log.warn("보관된 콜백을 적용하지 못했습니다. dispatchId={}", dispatch.getId(), exception);
        }
    }

    private void handleResponseFailure(LeasedDispatch leased, AnalysisDispatch dispatch, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 429) {
            updater.retryLater(leased, properties.retryDelay(dispatch.getAttemptCount() + 1), false, AnalysisDispatchErrorCode.AI_BUSY);
        } else if (REJECTED_STATUSES.contains(status)) {
            log.warn("AI가 분석 요청을 거절했습니다. dispatchId={} status={}", dispatch.getId(), status);
            failure.fail(leased, dispatch, AnalysisFailureCode.ANALYSIS_REJECTED);
        } else {
            retryOrFail(leased, dispatch, AnalysisDispatchErrorCode.AI_UNAVAILABLE, exception);
        }
    }

    private void retryOrFail(LeasedDispatch leased, AnalysisDispatch dispatch, AnalysisDispatchErrorCode errorCode, RuntimeException cause) {
        int attempts = dispatch.getAttemptCount() + 1;
        log.warn("AI에 분석 요청을 전달하지 못했습니다. dispatchId={} attempt={}", dispatch.getId(), attempts, cause);
        if (attempts >= properties.maxAttempts()) {
            failure.fail(leased, dispatch, AnalysisFailureCode.ANALYSIS_UNAVAILABLE);
            return;
        }
        Duration delay = properties.retryDelay(attempts);
        updater.retryLater(leased, delay, true, errorCode);
    }

    private AiImageAnalysisSubmitRequest request(AnalysisDispatch dispatch) {
        return new AiImageAnalysisSubmitRequest(dispatch.getRequestId(), new AiImageAnalysisSubmitRequest.Image(dispatch.getObjectKey(), dispatch.getSha256()),
                dispatch.getInputHint(), "ko-KR", "Asia/Seoul");
    }
}
