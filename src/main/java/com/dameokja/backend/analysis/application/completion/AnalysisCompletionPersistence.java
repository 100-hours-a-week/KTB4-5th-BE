package com.dameokja.backend.analysis.application.completion;

import com.dameokja.backend.analysis.application.job.AnalysisImageResult;
import com.dameokja.backend.analysis.application.job.AnalysisJobResult;
import com.dameokja.backend.analysis.domain.AiAnalysisIdentifiers;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobRequestRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Service
public class AnalysisCompletionPersistence {
    private final AnalysisJobRequestRepository analysisJobRequestRepository;
    private final AnalysisJobStateRepository analysisJobStateRepository;
    private final AnalysisCompletionValidator analysisCompletionValidator;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration resultTtl;

    public AnalysisCompletionPersistence(AnalysisJobRequestRepository analysisJobRequestRepository, AnalysisJobStateRepository analysisJobStateRepository,
            AnalysisCompletionValidator analysisCompletionValidator, ObjectMapper objectMapper, Clock clock, @Value("${analysis.result-ttl:7d}") Duration resultTtl) {
        if (resultTtl == null || resultTtl.toMillis() < 1) {
            throw new IllegalArgumentException("분석 결과 보관 TTL은 1밀리초 이상이어야 합니다.");
        }
        this.analysisJobRequestRepository = analysisJobRequestRepository;
        this.analysisJobStateRepository = analysisJobStateRepository;
        this.analysisCompletionValidator = analysisCompletionValidator;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.resultTtl = resultTtl;
    }

    @Transactional
    public List<AnalysisJob> apply(EventEnvelope<AnalysisCompletedPayload> analysisCompletedEvent) {
        AnalysisCompletion completion = validatedCompletion(analysisCompletedEvent);
        List<String> analysisJobIds = findJobIds(analysisCompletedEvent.payload().requestId());
        return applyToJobs(analysisJobIds, analysisCompletedEvent.payload(), completion);
    }

    private List<String> findJobIds(String requestId) {
        List<String> analysisJobIds = analysisJobRequestRepository.findAnalysisJobIds(requestId);
        if (analysisJobIds.isEmpty()) {
            throw new CustomException(AnalysisExceptionCode.NOT_FOUND);
        }
        return analysisJobIds;
    }

    private List<AnalysisJob> applyToJobs(List<String> analysisJobIds, AnalysisCompletedPayload payload, AnalysisCompletion completion) {
        List<AnalysisJob> completedAnalysisJobs = new ArrayList<>();
        int conflictCount = 0;
        // 같은 requestId를 공유하는 작업도 항상 같은 기본키 순서로 잠가 잠금 순서의 역전을 막는다.
        for (String analysisJobId : analysisJobIds) {
            AnalysisJobState analysisJobState = analysisJobStateRepository.findForUpdate(analysisJobId).orElse(null);
            if (isMissingOrExpired(analysisJobState)) {
                continue;
            }
            try {
                completedAnalysisJobs.add(applyToJob(analysisJobState, payload, completion));
            } catch (CustomException exception) {
                if (exception.getExceptionCode() != AnalysisExceptionCode.RESULT_CONFLICT) {
                    throw exception;
                }
                conflictCount++;
            }
        }
        if (conflictCount == analysisJobIds.size()) {
            throw new CustomException(AnalysisExceptionCode.RESULT_CONFLICT);
        }
        return List.copyOf(completedAnalysisJobs);
    }

    private boolean isMissingOrExpired(AnalysisJobState analysisJobState) {
        return analysisJobState == null || analysisJobState.getExpiresAt() != null && !BusinessTime.now(clock).isBefore(analysisJobState.getExpiresAt());
    }

    private AnalysisJob applyToJob(AnalysisJobState analysisJobState, AnalysisCompletedPayload payload, AnalysisCompletion completion) {
        AnalysisJob analysisJob = readJob(analysisJobState);
        validateRequestContext(analysisJob, payload);
        AnalysisJob completedAnalysisJob = completeImage(analysisJob, payload, completion);
        if (!completedAnalysisJob.equals(analysisJob)) {
            persistResultAndNotification(analysisJobState, completedAnalysisJob);
        }
        return completedAnalysisJob;
    }

    private AnalysisJob readJob(AnalysisJobState analysisJobState) {
        return objectMapper.readerFor(AnalysisJob.class).without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .readValue(analysisJobState.getAnalysisJobBody());
    }

    private void validateRequestContext(AnalysisJob analysisJob, AnalysisCompletedPayload payload) {
        if (!analysisJob.userId().equals(payload.userId())) {
            throw new IllegalArgumentException("분석 요청의 사용자와 완료 이벤트 사용자가 다릅니다.");
        }
        if (payload.refrigeratorId() != null && !payload.refrigeratorId().equals(analysisJob.refrigeratorId())) {
            throw new IllegalArgumentException("분석 요청의 냉장고와 완료 이벤트 냉장고가 다릅니다.");
        }
        AnalysisImage analysisImage = analysisJob.analysisImages().stream().filter(image -> image.requestId().equals(payload.requestId())).findFirst().orElseThrow();
        if (analysisImage.completion() != null && !payload.analysisId().equals(analysisImage.aiAnalysisId())) {
            throw new CustomException(AnalysisExceptionCode.RESULT_CONFLICT);
        }
    }

    private AnalysisJob completeImage(AnalysisJob analysisJob, AnalysisCompletedPayload payload, AnalysisCompletion completion) {
        return analysisJob.register(payload.requestId(), payload.analysisId()).complete(payload.analysisId(), completion,
                BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime().plus(resultTtl));
    }

    private void persistResultAndNotification(AnalysisJobState analysisJobState, AnalysisJob completedAnalysisJob) {
        AnalysisJobResult analysisJobResult = new AnalysisJobResult(completedAnalysisJob,
                completedAnalysisJob.analysisImages().stream().map(AnalysisImageResult::fromStored).toList());
        analysisJobState.updateResult(objectMapper.writeValueAsString(completedAnalysisJob), analysisJobResult.status(),
                completedAnalysisJob.expiresAt() == null ? null : completedAnalysisJob.expiresAt().toLocalDateTime());
        analysisJobState.scheduleNotification(BusinessTime.now(clock));
    }

    private AnalysisCompletion validatedCompletion(EventEnvelope<AnalysisCompletedPayload> analysisCompletedEvent) {
        try {
            AnalysisCompletedPayload payload = analysisCompletedEvent.payload();
            if (analysisCompletedEvent.messageId() == null || analysisCompletedEvent.messageId().isBlank()
                    || payload.occurredAt() == null || payload.requestId() == null || payload.requestId().isBlank()
                    || payload.userId() == null || !AiAnalysisIdentifiers.isValid(payload.analysisId())) {
                throw new IllegalArgumentException("완료 이벤트 필드가 올바르지 않습니다.");
            }
            AnalysisCompletion completion = payload.completion();
            analysisCompletionValidator.validate(completion);
            return completion;
        } catch (NullPointerException exception) {
            throw new IllegalArgumentException("완료 이벤트 필드가 누락되었습니다.", exception);
        }
    }
}
