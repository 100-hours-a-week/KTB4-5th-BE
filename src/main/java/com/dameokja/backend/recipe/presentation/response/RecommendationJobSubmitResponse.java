package com.dameokja.backend.recipe.presentation.response;

import com.dameokja.backend.recipe.application.RecommendationDailyGeneration;
import com.dameokja.backend.recipe.application.RecommendationSubmissionResult;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationJobSubmitResponse(@JsonInclude(JsonInclude.Include.ALWAYS) String jobId, String status, String mode,
        Integer pollAfterMs, String recommendationId, RecommendationDailyGeneration dailyGeneration) {
    private static final int POLL_AFTER_MS = 1000;

    public static RecommendationJobSubmitResponse from(RecommendationSubmissionResult recommendationSubmissionResult, String recommendationMode) {
        boolean alreadyUpToDate = recommendationSubmissionResult.recommendationId() != null;
        return new RecommendationJobSubmitResponse(recommendationSubmissionResult.eventId(), alreadyUpToDate ? "ALREADY_UP_TO_DATE" : "QUEUED",
                alreadyUpToDate ? null : recommendationMode == null ? "EXCLUDE_EXPIRED" : recommendationMode, alreadyUpToDate ? null : POLL_AFTER_MS,
                alreadyUpToDate ? recommendationSubmissionResult.recommendationId().toString() : null, recommendationSubmissionResult.dailyGeneration());
    }
}
