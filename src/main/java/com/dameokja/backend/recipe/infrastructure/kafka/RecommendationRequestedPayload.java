package com.dameokja.backend.recipe.infrastructure.kafka;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Strategy;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationRequestedPayload(Instant occurredAt, String requestId, Long userId, Long refrigeratorId, String inventoryFingerprint, Mode recommendationMode,
        List<AiRecipeInventoryItem> inventory, List<String> excludedIngredientNames, Integer limit, List<String> preferenceTags, Integer targetServings,
        List<Strategy> strategies, List<String> pantryIngredientNames, List<String> recentRecipeIds) {
    public RecommendationRequestedPayload {
        RecipeEventValidation.validate(occurredAt, requestId, userId, refrigeratorId);
        new AiRecipeRecommendationRequest(requestId, userId, refrigeratorId, inventoryFingerprint, recommendationMode, inventory,
                excludedIngredientNames, limit, preferenceTags, targetServings, strategies, pantryIngredientNames, recentRecipeIds);
    }

    public static RecommendationRequestedPayload from(AiRecipeRecommendationRequest request, Instant occurredAt) {
        return new RecommendationRequestedPayload(occurredAt, request.requestId(), request.userId(), request.refrigeratorId(),
                request.inventoryFingerprint(), request.recommendationMode(), request.inventory(), request.excludedIngredientNames(),
                request.limit(), request.preferenceTags(), request.targetServings(), request.strategies(), request.pantryIngredientNames(), request.recentRecipeIds());
    }

    public AiRecipeRecommendationRequest toRequest() {
        return new AiRecipeRecommendationRequest(requestId, userId, refrigeratorId, inventoryFingerprint, recommendationMode, inventory,
                excludedIngredientNames, limit, preferenceTags, targetServings, strategies, pantryIngredientNames, recentRecipeIds);
    }
}
