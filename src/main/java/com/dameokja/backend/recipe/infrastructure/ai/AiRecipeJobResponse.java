package com.dameokja.backend.recipe.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.web.client.RestClientException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRecipeJobResponse(String jobId, String requestId, Long userId, Long refrigeratorId, Status status,
        OffsetDateTime submittedAt, OffsetDateTime startedAt, OffsetDateTime completedAt, AiRecipeRecommendationResponse result, Error error) {
    public enum Status { QUEUED, PROCESSING, COMPLETED, FAILED }

    public AiRecipeJobResponse {
        if (jobId == null || jobId.isBlank() || requestId == null || requestId.isBlank() || refrigeratorId == null || refrigeratorId <= 0
                || (userId != null && userId <= 0) || status == null || submittedAt == null) {
            throw new RestClientException("AI 추천 작업 응답 형식이 올바르지 않습니다.");
        }
        boolean valid = switch (status) {
            case QUEUED, PROCESSING -> result == null && error == null && completedAt == null;
            case COMPLETED -> result != null && error == null && completedAt != null;
            case FAILED -> result == null && error != null && completedAt != null;
        };
        if (!valid) {
            throw new RestClientException("AI 추천 작업 상태와 결과가 일치하지 않습니다.");
        }
    }

    public void validateFor(String expectedJobId, AiRecipeRecommendationRequest request) {
        if (!Objects.equals(jobId, expectedJobId) || !request.requestId().equals(requestId)
                || !Objects.equals(userId, request.userId()) || !Objects.equals(refrigeratorId, request.refrigeratorId())) {
            throw new RestClientException("AI 추천 작업 응답이 요청 작업·요청 ID·사용자·냉장고와 일치하지 않습니다.");
        }
        if (result != null) {
            result.validateFor(request);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(String code, String message, Boolean retryable) {
        public Error {
            if (code == null || code.isBlank() || message == null || retryable == null) {
                throw new RestClientException("AI 추천 작업 오류 형식이 올바르지 않습니다.");
            }
        }
    }
}
