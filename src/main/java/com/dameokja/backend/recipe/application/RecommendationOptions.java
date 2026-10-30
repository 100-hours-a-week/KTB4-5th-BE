package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.ai.AiInventoryFingerprint;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Strategy;
import java.util.List;

public record RecommendationOptions(Mode recommendationMode, List<String> preferenceTags, Integer limit, Integer targetServings,
        List<Strategy> strategies, List<String> excludedIngredientNames, List<String> pantryIngredientNames, List<String> recentRecipeIds) {
    public static RecommendationOptions empty() { return new RecommendationOptions(null, null, null, null, null, null, null, null); }

    public AiRecipeRecommendationRequest toRequest(String requestId, Long userId, Long refrigeratorId, List<AiRecipeInventoryItem> inventory) {
        return new AiRecipeRecommendationRequest(requestId, userId, refrigeratorId, AiInventoryFingerprint.of(inventory), recommendationMode,
                inventory, excludedIngredientNames, limit, preferenceTags, targetServings, strategies, pantryIngredientNames, recentRecipeIds);
    }
}
