package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalysisFeedbackSubmittedEvent(String messageId, Payload payload) {
    public static AnalysisFeedbackSubmittedEvent create(Long userId, String requestId, String analysisId, AiAnalysisFeedbackRequest feedbackRequest, Instant occurredAt) {
        return new AnalysisFeedbackSubmittedEvent(UUID.randomUUID().toString(), new Payload("AnalysisFeedbackSubmitted", occurredAt, requestId, userId, analysisId,
                feedbackRequest.refrigeratorId(), feedbackRequest.consentForModelImprovement(), feedbackRequest.items()));
    }

    public record Payload(String eventType, Instant occurredAt, String requestId, Long userId, String analysisId,
            Long refrigeratorId, boolean consentForModelImprovement, List<AiAnalysisFeedbackRequest.Item> items) {
        public Payload {
            items = List.copyOf(items);
        }
    }
}
