package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import com.dameokja.backend.analysis.infrastructure.AiImageAnalysisResponse;
import com.dameokja.backend.analysis.infrastructure.AnalysisDispatchRepository;
import com.dameokja.backend.global.exception.CustomException;
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
    private final AnalysisDispatchRepository analysisDispatchRepository;
    private final AiAnalysisClient aiAnalysisClient;
    private final AnalysisDispatchUpdater analysisDispatchUpdater;
    private final AnalysisDispatchFailure analysisDispatchFailure;
    private final AnalysisCompletionApplier analysisCompletionApplier;
    private final AnalysisCompletionValidator analysisCompletionValidator;
    private final AnalysisDispatchProperties analysisDispatchProperties;
    private final Clock clock;

    public void check(LeasedDispatch leasedDispatch) {
        analysisDispatchRepository.findLeased(leasedDispatch.id(), leasedDispatch.token())
                .filter(analysisDispatch -> analysisDispatch.getStatus() == AnalysisDispatchStatus.CHECKING)
                .ifPresent(analysisDispatch -> check(leasedDispatch, analysisDispatch));
    }

    private void check(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch) {
        if (appliedParked(analysisDispatch)) {
            return;
        }
        if (analysisDispatch.isExpired(BusinessTime.now(clock))) {
            analysisDispatchFailure.fail(leasedDispatch, analysisDispatch, AnalysisFailureCode.ANALYSIS_EXPIRED);
            return;
        }
        try {
            AiImageAnalysisResponse aiImageAnalysisResponse = aiAnalysisClient.get(analysisDispatch.getAiAnalysisId());
            if (aiImageAnalysisResponse.isFinished()) {
                recover(leasedDispatch, analysisDispatch, aiImageAnalysisResponse);
                return;
            }
            analysisDispatchUpdater.extend(leasedDispatch, aiImageAnalysisResponse.status());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                resend(leasedDispatch, analysisDispatch);
            } else {
                analysisDispatchUpdater.extend(leasedDispatch, UNREACHABLE);
            }
        } catch (RestClientException exception) {
            analysisDispatchUpdater.extend(leasedDispatch, UNREACHABLE);
        }
    }

    // AI는 콜백 재시도가 끝나면 다시 보내지 않으므로, 기한까지 콜백이 없으면 조회 결과로 완료한다.
    private void recover(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch, AiImageAnalysisResponse aiImageAnalysisResponse) {
        AnalysisCompletion completion;
        try {
            completion = aiImageAnalysisResponse.toCompletion();
            analysisCompletionValidator.validate(completion);
        } catch (RuntimeException exception) {
            // 콜백이었어도 거절됐을 결과라 기다려도 완료할 수 없다. 슬롯을 계속 잡지 않도록 바로 실패로 끝낸다.
            log.error("AI 조회 결과를 분석 결과로 바꿀 수 없습니다. dispatchId={}", analysisDispatch.getId(), exception);
            analysisDispatchFailure.fail(leasedDispatch, analysisDispatch, AnalysisFailureCode.ANALYSIS_UNAVAILABLE);
            return;
        }
        try {
            analysisCompletionApplier.apply(analysisDispatch.getAiAnalysisId(), completion);
            log.info("콜백 없이 AI 조회 결과로 완료했습니다. dispatchId={}", analysisDispatch.getId());
        } catch (CustomException exception) {
            if (exception.getExceptionCode() == AnalysisExceptionCode.RESULT_CONFLICT) {
                // 늦게 온 콜백이 먼저 다른 결과를 저장했다. 그 결과가 이미 전달 행을 끝냈다.
                return;
            }
            analysisDispatchUpdater.extend(leasedDispatch, aiImageAnalysisResponse.status());
        }
    }

    // 보관된 콜백 적용이 계속 실패해도 기한 판단은 이어가야 작업이 끝없이 확인 상태에 머물지 않는다.
    private boolean appliedParked(AnalysisDispatch analysisDispatch) {
        try {
            return analysisCompletionApplier.applyParked(analysisDispatch.getAiAnalysisId());
        } catch (RuntimeException exception) {
            log.warn("보관된 콜백을 적용하지 못했습니다. dispatchId={}", analysisDispatch.getId(), exception);
            return false;
        }
    }

    private void resend(LeasedDispatch leasedDispatch, AnalysisDispatch analysisDispatch) {
        int attempts = analysisDispatch.getAttemptCount() + 1;
        log.warn("AI에서 작업을 찾지 못해 같은 requestId로 다시 보냅니다. dispatchId={} attempt={}", analysisDispatch.getId(), attempts);
        if (attempts >= analysisDispatchProperties.maxAttempts()) {
            analysisDispatchFailure.fail(leasedDispatch, analysisDispatch, AnalysisFailureCode.ANALYSIS_UNAVAILABLE);
            return;
        }
        analysisDispatchUpdater.retryLater(leasedDispatch, Duration.ZERO, true, AnalysisDispatchErrorCode.AI_JOB_LOST);
    }
}
