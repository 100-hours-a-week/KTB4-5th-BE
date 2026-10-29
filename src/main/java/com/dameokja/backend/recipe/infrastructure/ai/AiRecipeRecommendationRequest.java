package com.dameokja.backend.recipe.infrastructure.ai;

import com.dameokja.backend.ingredient.domain.Ingredient;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record AiRecipeRecommendationRequest(String requestId, Long refrigeratorId, String inventoryFingerprint, Mode recommendationMode,
        List<AiRecipeInventoryItem> inventory, List<String> excludedIngredientNames, int limit) {
    private static final int MAX_INVENTORY = 300;
    private static final int MAX_EXCLUSIONS = 100;
    private static final int MAX_LIMIT = 10;

    public enum Mode { INCLUDE_EXPIRED, EXCLUDE_EXPIRED }

    public AiRecipeRecommendationRequest {
        inventory = List.copyOf(inventory);
        excludedIngredientNames = List.copyOf(excludedIngredientNames);
        Objects.requireNonNull(recommendationMode);
        if (requestId == null || requestId.isBlank() || requestId.length() > 500 || refrigeratorId == null || refrigeratorId <= 0
                || inventory.size() > MAX_INVENTORY || excludedIngredientNames.size() > MAX_EXCLUSIONS || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("AI 추천 요청 범위가 올바르지 않습니다.");
        }
        HashSet<Long> identifiers = new HashSet<>();
        for (AiRecipeInventoryItem item : inventory) {
            if (item.ingredientId() == null || item.ingredientId() <= 0 || !identifiers.add(item.ingredientId())) {
                throw new IllegalArgumentException("AI 추천 재고 ID가 없거나 중복됩니다.");
            }
        }
        if (!AiInventoryFingerprint.of(inventory).equals(inventoryFingerprint)) {
            throw new IllegalArgumentException("AI 추천 재고 지문이 일치하지 않습니다.");
        }
    }

    public static AiRecipeRecommendationRequest create(String requestId, Long refrigeratorId, Mode mode,
            List<AiRecipeInventoryItem> inventory, List<String> excludedIngredientNames, int limit) {
        return new AiRecipeRecommendationRequest(requestId, refrigeratorId, AiInventoryFingerprint.of(inventory),
                mode, inventory, excludedIngredientNames, limit);
    }

    public static AiRecipeRecommendationRequest from(String requestId, Long refrigeratorId, Mode mode,
            List<Ingredient> ingredients, List<String> excludedIngredientNames, int limit) {
        for (Ingredient ingredient : ingredients) {
            if (!Objects.equals(refrigeratorId, ingredient.getRefrigerator().getId())) {
                throw new IllegalArgumentException("요청 냉장고의 재고만 추천에 전달할 수 있습니다.");
            }
        }
        return create(requestId, refrigeratorId, mode, ingredients.stream().map(AiRecipeInventoryItem::from).toList(), excludedIngredientNames, limit);
    }
}
