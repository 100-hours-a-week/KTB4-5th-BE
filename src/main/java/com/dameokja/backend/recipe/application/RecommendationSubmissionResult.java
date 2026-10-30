package com.dameokja.backend.recipe.application;

import java.time.LocalDateTime;

public record RecommendationSubmissionResult(Long sequence, String eventId, String requestId, Long refrigeratorId,
        LocalDateTime acceptedAt, RecommendationDailyGeneration dailyGeneration, Long recommendationId) {
    public RecommendationSubmissionResult(Long sequence, String eventId, String requestId, Long refrigeratorId, LocalDateTime acceptedAt) {
        this(sequence, eventId, requestId, refrigeratorId, acceptedAt, null, null);
    }

    public RecommendationSubmissionResult withDailyGeneration(RecommendationDailyGeneration dailyGeneration) {
        return new RecommendationSubmissionResult(sequence, eventId, requestId, refrigeratorId, acceptedAt, dailyGeneration, recommendationId);
    }
}
