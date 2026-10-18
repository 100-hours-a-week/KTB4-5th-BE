package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 전달 행이 없는 작업(전달 큐 이전 접수, DB 초기화)은 워커가 끝내지 못해 TTL 없이 진행 중으로 남는다.
// 워커가 끝냈어야 할 시각이 지나도 결과 보관 기한이 없으면 조회 시점에 기한 만료로 기록해 사용자가 지울 수 있게 한다.
@Component
@RequiredArgsConstructor
public class AnalysisOverdueJobExpirer {
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisDispatchProperties analysisDispatchProperties;
    private final Clock clock;

    public AnalysisJob expireIfOverdue(AnalysisJob analysisJob) {
        if (analysisJob.expiresAt() != null || !isOverdue(analysisJob)) {
            return analysisJob;
        }
        AnalysisCompletion failedCompletion = new AnalysisCompletion(AnalysisStatus.FAILED, BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime(),
                null, AnalysisFailureCode.ANALYSIS_EXPIRED.toFailure());
        AnalysisJob expiredJob = analysisJob;
        for (AnalysisImage analysisImage : analysisJob.analysisImages()) {
            if (analysisImage.completion() == null) {
                expiredJob = recordFailure(expiredJob, analysisImage.requestId(), failedCompletion);
            }
        }
        return expiredJob;
    }

    // 워커가 같은 기한에 행을 선점해 실패를 기록하는 중일 수 있어 선점 기간만큼 더 기다린다.
    private boolean isOverdue(AnalysisJob analysisJob) {
        Instant overdueAt = analysisJob.submittedAt().toInstant().plus(analysisDispatchProperties.expiresAfter())
                .plus(analysisDispatchProperties.leaseDuration());
        return !clock.instant().isBefore(overdueAt);
    }

    // 늦은 콜백이나 워커가 먼저 결과를 저장했으면 그 결과를 그대로 둔다.
    private AnalysisJob recordFailure(AnalysisJob analysisJob, String requestId, AnalysisCompletion failedCompletion) {
        try {
            return analysisJobStore.fail(analysisJob.id(), requestId, failedCompletion);
        } catch (CustomException exception) {
            if (exception.getExceptionCode() != AnalysisExceptionCode.RESULT_CONFLICT) {
                throw exception;
            }
            return analysisJobStore.find(analysisJob.id()).orElse(analysisJob);
        }
    }
}
