package com.dameokja.backend.analysis.application.job;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisJobState;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStateRepository;
import com.dameokja.backend.analysis.infrastructure.AnalysisOutboxRepository;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaProperties;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisRequestedEvent;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
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
    private final UserAccessService userAccessService;
    private final AnalysisJobStateRepository analysisJobStateRepository;
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
        User user = userAccessService.getActive(userId);
        OffsetDateTime submittedAt = BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime();
        AnalysisJob analysisJob = new AnalysisJob(UUID.randomUUID().toString(), userId, inputHint, analysisImages, submittedAt, null, false);
        AnalysisJobState analysisJobState = analysisJobStateRepository.save(AnalysisJobState.queued(user, analysisJob.id(), objectMapper.writeValueAsString(analysisJob)));
        for (AnalysisImage analysisImage : analysisImages) {
            AnalysisRequestedEvent analysisRequestedEvent = AnalysisRequestedEvent.create(userId, analysisImage, inputHint, submittedAt.toInstant());
            analysisOutboxRepository.save(AnalysisOutbox.pending(analysisJobState, analysisRequestedEvent.messageId(),
                    analysisKafkaProperties.requestTopic(), objectMapper.writeValueAsString(analysisRequestedEvent), submittedAt.toLocalDateTime()));
        }
        return analysisJob;
    }
}
