package com.dameokja.backend.recipe.infrastructure.kafka;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse.Error;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse.Status;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse;
import java.time.Instant;
import java.time.OffsetDateTime;

public record RecommendationCompletedPayload(Instant occurredAt, String requestId, Long userId, Long refrigeratorId, String jobId, Status status,
        OffsetDateTime submittedAt, OffsetDateTime startedAt, OffsetDateTime completedAt, AiRecipeRecommendationResponse result, Error error) {
    public RecommendationCompletedPayload {
        if (occurredAt == null || requestId == null || requestId.isBlank() || userId == null || userId <= 0 || refrigeratorId == null || refrigeratorId <= 0) {
            throw new IllegalArgumentException("추천 완료 이벤트의 시각·요청·사용자·냉장고가 올바르지 않습니다.");
        }
        if (status != Status.COMPLETED && status != Status.FAILED) {
            throw new IllegalArgumentException("종료된 추천 작업만 완료 이벤트로 받을 수 있습니다.");
        }
        new AiRecipeJobResponse(jobId, requestId, userId, refrigeratorId, status, submittedAt, startedAt, completedAt, result, error);
    }

    public AiRecipeJobResponse toResponse() {
        return new AiRecipeJobResponse(jobId, requestId, userId, refrigeratorId, status, submittedAt, startedAt, completedAt, result, error);
    }
}
