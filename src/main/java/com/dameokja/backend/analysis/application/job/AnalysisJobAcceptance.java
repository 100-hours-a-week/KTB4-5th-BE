package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisJobRequest;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.domain.AnalysisRequestId;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobRequestRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxRepository;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaProperties;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisRequestedPayload;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AnalysisJobAcceptance {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final UserAccessService userAccessService;
    private final AnalysisJobStateRepository analysisJobStateRepository;
    private final AnalysisJobRequestRepository analysisJobRequestRepository;
    private final AnalysisOutboxRepository analysisOutboxRepository;
    private final AnalysisKafkaProperties analysisKafkaProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public AnalysisJob accept(Long userId, String inputHint, List<AnalysisImage> analysisImages) {
        inputHint = inputHint == null ? "AUTO" : inputHint;
        if (userId == null || analysisImages == null || analysisImages.isEmpty() || !Set.of("AUTO", "RECEIPT", "PRODUCT").contains(inputHint)) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        Long refrigeratorId = refrigeratorAccessService.lockCurrentForWrite(userId).getId();
        String resolvedInputHint = inputHint;
        analysisImages = analysisImages.stream().map(image -> AnalysisImage.pending(image.objectKey(), image.sha256(),
                        AnalysisRequestId.generate(userId, refrigeratorId, image, resolvedInputHint))).toList();
        User user = userAccessService.getActive(userId);
        OffsetDateTime submittedAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime();
        AnalysisJob analysisJob = new AnalysisJob(UUID.randomUUID().toString(), userId, refrigeratorId, inputHint, analysisImages, submittedAt, null, false);
        AnalysisJobState analysisJobState = analysisJobStateRepository.save(AnalysisJobState.queued(user, analysisJob.id(), objectMapper.writeValueAsString(analysisJob)));
        for (AnalysisImage analysisImage : analysisImages) {
            analysisJobRequestRepository.save(new AnalysisJobRequest(analysisJobState, analysisImage.requestId()));
            EventEnvelope<AnalysisRequestedPayload> analysisRequestedEvent =
                    EventEnvelope.create(AnalysisRequestedPayload.create(userId, refrigeratorId, analysisImage, inputHint, submittedAt.toInstant()));
            analysisOutboxRepository.save(AnalysisOutbox.pending(analysisJobState, analysisRequestedEvent.messageId(),
                    analysisKafkaProperties.requestTopic(), objectMapper.writeValueAsString(analysisRequestedEvent), submittedAt.toLocalDateTime()));
        }
        return analysisJob;
    }
}
