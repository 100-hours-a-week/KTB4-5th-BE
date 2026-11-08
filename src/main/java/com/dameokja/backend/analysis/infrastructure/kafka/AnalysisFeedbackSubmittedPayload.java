package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import java.time.Instant;
import java.util.List;

public record AnalysisFeedbackSubmittedPayload(Instant occurredAt, String requestId, Long userId, String analysisId,
        Long refrigeratorId, boolean consentForModelImprovement, List<AiAnalysisFeedbackRequest.Item> items) {
    public AnalysisFeedbackSubmittedPayload {
        items = List.copyOf(items);
    }

    public static AnalysisFeedbackSubmittedPayload create(Long userId, String requestId, String analysisId, AiAnalysisFeedbackRequest request, Instant occurredAt) {
        return new AnalysisFeedbackSubmittedPayload(occurredAt, requestId, userId, analysisId,
                request.refrigeratorId(), request.consentForModelImprovement(), request.items());
    }
}
