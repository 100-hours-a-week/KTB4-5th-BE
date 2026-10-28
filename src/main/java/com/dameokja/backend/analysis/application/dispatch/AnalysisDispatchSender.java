package com.dameokja.backend.analysis.application.dispatch;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionApplier;
import com.dameokja.backend.analysis.domain.AiAnalysisIdentifiers;
import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitRequest;
import com.dameokja.backend.analysis.infrastructure.ai.AiImageAnalysisSubmitResponse;
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
    private final AnalysisDispatchRepository analysisDispatchRepository;
    private final AnalysisJobStore analysisJobStore;
    private final AiAnalysisClient aiAnalysisClient;
    private final AnalysisDispatchUpdater analysisDispatchUpdater;
    private final AnalysisDispatchFailure analysisDispatchFailure;
    private final AnalysisCompletionApplier analysisCompletionApplier;
    private final AnalysisDispatchProperties analysisDispatchProperties;
    private final Clock clock;

    public void send(LeasedDispatch leasedDispatch) {
        analysisDispatchRepository.findLeased(leasedDispatch.id(), leasedDispatch.token())
                .filter(analysisDispatch -> analysisDispatch.getStatus() == AnalysisDispatchStatus.DISPATCHING)
                .ifPresent(analysisDispatch -> send(leasedDispatch, analysisDispatch));
    }

    private void send(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch) {
        LocalDateTime now = BusinessTime.now(clock);
        if (analysisDispatch.isExpired(now)) {
            analysisDispatchFailure.fail(leasedDispatch, analysisDispatch, AnalysisFailureCode.ANALYSIS_EXPIRED);
        } else if (analysisJobStore.find(analysisDispatch.getAnalysisJobId()).isPresent()) {
            submit(leasedDispatch, analysisDispatch);
        } else if (!now.isBefore(analysisDispatch.getCreatedAt().plus(analysisDispatchProperties.orphanGrace()))) {
            analysisDispatchUpdater.fail(leasedDispatch, AnalysisDispatchErrorCode.JOB_MISSING);
        } else {
            // 접수 API가 큐 저장 직후 Redis 작업을 만드는 중일 수 있다.
            analysisDispatchUpdater.retryLater(leasedDispatch, analysisDispatchProperties.retryDelay(), false, AnalysisDispatchErrorCode.JOB_NOT_READY);
        }
    }

    private void submit(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch) {
        AiImageAnalysisSubmitResponse aiImageAnalysisResponse;
        try {
            aiImageAnalysisResponse = aiAnalysisClient.submit(request(analysisDispatch));
        } catch (RestClientResponseException exception) {
            handleResponseFailure(leasedDispatch, analysisDispatch, exception);
            return;
        } catch (RestClientException exception) {
            retryOrFail(leasedDispatch, analysisDispatch, AnalysisDispatchErrorCode.AI_UNAVAILABLE, exception);
            return;
        }
        String analysisId = aiImageAnalysisResponse.analysisId();
        if (!AiAnalysisIdentifiers.isValid(analysisId)) {
            retryOrFail(leasedDispatch, analysisDispatch, AnalysisDispatchErrorCode.AI_UNAVAILABLE, null);
            return;
        }
        register(leasedDispatch, analysisDispatch, analysisId, FINISHED_STATUSES.contains(aiImageAnalysisResponse.status()));
    }

    // Redis에 AI ID를 먼저 등록해야 빠르게 도착한 콜백이 묶음을 찾는다.
    // 같은 사진의 재요청은 AI가 끝낸 분석을 그대로 돌려주고 콜백을 다시 보내지 않으므로, 기한을 지금으로 두어 바로 조회한다.
    private void register(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch, String analysisId, boolean alreadyFinished) {
        try {
            analysisJobStore.register(analysisDispatch.getAnalysisJobId(), analysisDispatch.getUserId(), analysisDispatch.getRequestId(), analysisId);
        } catch (RuntimeException exception) {
            log.warn("AI 접수 ID를 등록하지 못해 같은 requestId로 다시 보냅니다. dispatchId={}", analysisDispatch.getId(), exception);
            analysisDispatchUpdater.retryLater(leasedDispatch, analysisDispatchProperties.retryDelay(), false, AnalysisDispatchErrorCode.REGISTER_FAILED);
            return;
        }
        analysisDispatchUpdater.accept(leasedDispatch, analysisId, alreadyFinished);
        applyParked(analysisDispatch, analysisId);
    }

    // 첫 접수 응답이 유실된 사이 도착해 보관된 콜백이 있으면 지금 적용한다. 실패하면 기한 확인에서 다시 적용한다.
    private void applyParked(AnalysisDispatch analysisDispatch, String analysisId) {
        try {
            if (analysisCompletionApplier.applyParked(analysisId)) {
                log.info("등록 전에 도착한 콜백을 적용했습니다. dispatchId={} analysisId={}", analysisDispatch.getId(), analysisId);
            }
        } catch (RuntimeException exception) {
            log.warn("보관된 콜백을 적용하지 못했습니다. dispatchId={}", analysisDispatch.getId(), exception);
        }
    }

    private void handleResponseFailure(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch, RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        if (status == 429) {
            analysisDispatchUpdater.retryLater(leasedDispatch, analysisDispatchProperties.retryDelay(analysisDispatch.getAttemptCount() + 1), false, AnalysisDispatchErrorCode.AI_BUSY);
        } else if (REJECTED_STATUSES.contains(status)) {
            log.warn("AI가 분석 요청을 거절했습니다. dispatchId={} status={}", analysisDispatch.getId(), status);
            analysisDispatchFailure.fail(leasedDispatch, analysisDispatch, AnalysisFailureCode.ANALYSIS_REJECTED);
        } else {
            retryOrFail(leasedDispatch, analysisDispatch, AnalysisDispatchErrorCode.AI_UNAVAILABLE, exception);
        }
    }

    private void retryOrFail(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch, AnalysisDispatchErrorCode errorCode, RuntimeException cause) {
        int attempts = analysisDispatch.getAttemptCount() + 1;
        log.warn("AI에 분석 요청을 전달하지 못했습니다. dispatchId={} attempt={}", analysisDispatch.getId(), attempts, cause);
        if (attempts >= analysisDispatchProperties.maxAttempts()) {
            analysisDispatchFailure.fail(leasedDispatch, analysisDispatch, AnalysisFailureCode.ANALYSIS_UNAVAILABLE);
            return;
        }
        Duration delay = analysisDispatchProperties.retryDelay(attempts);
        analysisDispatchUpdater.retryLater(leasedDispatch, delay, true, errorCode);
    }

    private AiImageAnalysisSubmitRequest request(AnalysisDispatch analysisDispatch) {
        return new AiImageAnalysisSubmitRequest(analysisDispatch.getRequestId(), analysisDispatch.getUserId(),
                new AiImageAnalysisSubmitRequest.Image(analysisDispatch.getObjectKey(), analysisDispatch.getSha256()), analysisDispatch.getInputHint(), "ko-KR", "Asia/Seoul");
    }
}
