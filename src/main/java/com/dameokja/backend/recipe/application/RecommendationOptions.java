package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.ai.AiInventoryFingerprint;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Strategy;
import java.util.List;
import java.util.stream.Stream;

public record RecommendationOptions(Mode recommendationMode, List<String> preferenceTags, Integer limit, Integer targetServings,
        List<Strategy> strategies, List<String> excludedIngredientNames, List<String> pantryIngredientNames, List<String> recentRecipeIds) {
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_SERVINGS = 2;
    public static RecommendationOptions empty() { return new RecommendationOptions(null, null, null, null, null, null, null, null); }

    public static RecommendationOptions fromInput(String recommendationMode, List<String> preferenceTags, Integer limit, Integer targetServings,
            List<String> strategies, List<String> excludedIngredientNames, List<String> pantryIngredientNames, List<String> recentRecipeIds) {
        return new RecommendationOptions(recommendationMode == null ? null : Mode.valueOf(recommendationMode), preferenceTags, limit, targetServings,
                strategies == null ? null : strategies.stream().map(Strategy::valueOf).toList(), excludedIngredientNames, pantryIngredientNames, recentRecipeIds);
    }

    public static RecommendationOptions from(AiRecipeRecommendationRequest recommendationRequest) {
        return new RecommendationOptions(recommendationRequest.recommendationMode(), recommendationRequest.preferenceTags(), recommendationRequest.limit(),
                recommendationRequest.targetServings(), recommendationRequest.strategies(), recommendationRequest.excludedIngredientNames(),
                recommendationRequest.pantryIngredientNames(), recommendationRequest.recentRecipeIds());
    }

    public RecommendationOptions effective() {
        List<Strategy> effectiveStrategies = strategies == null ? List.of(Strategy.values())
                : Stream.concat(Stream.of(Strategy.USE_EXPIRING), strategies.stream()).distinct().toList();
        return new RecommendationOptions(recommendationMode == null ? Mode.EXCLUDE_EXPIRED : recommendationMode, orEmpty(preferenceTags),
                limit == null ? DEFAULT_LIMIT : limit, targetServings == null ? DEFAULT_SERVINGS : targetServings, effectiveStrategies,
                orEmpty(excludedIngredientNames), orEmpty(pantryIngredientNames), orEmpty(recentRecipeIds));
    }

    private static <T> List<T> orEmpty(List<T> selectedValues) { return selectedValues == null ? List.of() : selectedValues; }

    public AiRecipeRecommendationRequest toRequest(String requestId, Long userId, Long refrigeratorId, List<AiRecipeInventoryItem> inventory) {
        return new AiRecipeRecommendationRequest(requestId, userId, refrigeratorId, AiInventoryFingerprint.of(inventory), recommendationMode,
                inventory, excludedIngredientNames, limit, preferenceTags, targetServings, strategies, pantryIngredientNames, recentRecipeIds);
    }
}
