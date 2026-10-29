package com.dameokja.backend.recipe.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import org.springframework.web.client.RestClientException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRecipeSubmitResponse(String jobId, String requestId, AiRecipeJobResponse.Status status, OffsetDateTime submittedAt) {
    public AiRecipeSubmitResponse {
        if (jobId == null || jobId.isBlank() || requestId == null || requestId.isBlank() || status == null || submittedAt == null) {
            throw new RestClientException("AI 추천 접수 응답 형식이 올바르지 않습니다.");
        }
    }

    void validateFor(AiRecipeRecommendationRequest request) {
        if (!request.requestId().equals(requestId)) {
            throw new RestClientException("AI 추천 접수 응답이 요청 ID와 일치하지 않습니다.");
        }
    }
}
