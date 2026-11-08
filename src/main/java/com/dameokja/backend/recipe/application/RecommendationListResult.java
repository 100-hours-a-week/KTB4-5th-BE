package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationState.GenerationStatus;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationTrigger;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecommendationListResult(String recommendationId, Status status, OffsetDateTime generatedAt, String activeJobId,
        GenerationTrigger generationTrigger, GenerationStatus generationStatus, boolean hasNewRecommendation,
        RecommendationDailyGeneration dailyGeneration, List<RecipeSummary> recipes) {
    public enum Status { READY, STALE, GENERATING, EMPTY }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecipeSummary(String recipeId, String name, String imageUrl, Integer cookingTimeMinutes, IngredientCounts ingredientCounts) {}
    public record IngredientCounts(Integer total, Integer owned, Integer expiring) {}
}
