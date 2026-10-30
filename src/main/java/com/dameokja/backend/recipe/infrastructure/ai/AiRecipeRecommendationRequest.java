package com.dameokja.backend.recipe.infrastructure.ai;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiRecipeRecommendationRequest(String requestId, Long refrigeratorId, String inventoryFingerprint, Mode recommendationMode,
        List<AiRecipeInventoryItem> inventory, List<String> excludedIngredientNames, Integer limit, List<String> preferenceTags,
        Integer targetServings, List<Strategy> strategies, List<String> pantryIngredientNames, List<String> recentRecipeIds) {
    private static final int MAX_INVENTORY = 300;
    private static final int MAX_EXCLUDED_NAMES = 100;
    private static final int MAX_RECENT_RECIPES = 100;
    private static final int MAX_LIMIT = 10;
    private static final int MAX_SERVINGS = 12;
    private static final int MAX_TAGS = 12;
    private static final int MAX_TAG_LENGTH = 40;
    private static final int MAX_PANTRY_NAMES = 30;
    private static final int MAX_NAME_LENGTH = 500;

    public enum Mode { INCLUDE_EXPIRED, EXCLUDE_EXPIRED }
    public enum Strategy { USE_EXPIRING, MINIMIZE_SHOPPING, SIMPLE_COOKING }

    public AiRecipeRecommendationRequest {
        inventory = List.copyOf(inventory);
        excludedIngredientNames = copyWithinLimit(excludedIngredientNames, MAX_EXCLUDED_NAMES);
        preferenceTags = copyWithinLimit(preferenceTags, MAX_TAGS);
        strategies = copyWithinLimit(strategies, Strategy.values().length);
        pantryIngredientNames = copyWithinLimit(pantryIngredientNames, MAX_PANTRY_NAMES);
        recentRecipeIds = copyWithinLimit(recentRecipeIds, MAX_RECENT_RECIPES);
        validateRange(limit, MAX_LIMIT);
        validateRange(targetServings, MAX_SERVINGS);
        validateNames(preferenceTags, MAX_TAG_LENGTH);
        validateNames(pantryIngredientNames, MAX_NAME_LENGTH);
        if (strategies != null && (strategies.isEmpty() || new HashSet<>(strategies).size() != strategies.size())) {
            throw new IllegalArgumentException("추천 전략은 비어 있거나 중복될 수 없습니다.");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 500 || refrigeratorId == null || refrigeratorId <= 0 || inventory.size() > MAX_INVENTORY) {
            throw new IllegalArgumentException("AI 추천 요청 범위가 올바르지 않습니다.");
        }
        validateInventory(inventory);
        if (!AiInventoryFingerprint.of(inventory).equals(inventoryFingerprint)) {
            throw new IllegalArgumentException("AI 추천 재고 지문이 일치하지 않습니다.");
        }
    }

    public AiRecipeRecommendationRequest(String requestId, Long refrigeratorId, String inventoryFingerprint, Mode recommendationMode,
            List<AiRecipeInventoryItem> inventory, List<String> excludedIngredientNames, int limit) {
        this(requestId, refrigeratorId, inventoryFingerprint, recommendationMode, inventory, excludedIngredientNames, limit, null, null, null, null, null);
    }

    public Mode effectiveMode() { return recommendationMode == null ? Mode.EXCLUDE_EXPIRED : recommendationMode; }

    private static <T> List<T> copyWithinLimit(List<T> values, int maximum) {
        if (values == null) { return null; }
        if (values.size() > maximum) { throw new IllegalArgumentException("AI 추천 목록의 최대 개수를 초과했습니다."); }
        return List.copyOf(values);
    }

    private static void validateRange(Integer value, int maximum) {
        if (value != null && (value < 1 || value > maximum)) { throw new IllegalArgumentException("AI 추천 수치 범위가 올바르지 않습니다."); }
    }

    private static void validateNames(List<String> names, int maximumLength) {
        if (names != null && names.stream().anyMatch(name -> name.isBlank() || name.codePointCount(0, name.length()) > maximumLength)) {
            throw new IllegalArgumentException("AI 추천 이름 길이가 올바르지 않습니다.");
        }
    }

    private static void validateInventory(List<AiRecipeInventoryItem> inventory) {
        HashSet<Long> identifiers = new HashSet<>();
        for (AiRecipeInventoryItem inventoryItem : inventory) {
            if (inventoryItem.ingredientId() == null || inventoryItem.ingredientId() <= 0 || !identifiers.add(inventoryItem.ingredientId())) {
                throw new IllegalArgumentException("AI 추천 재고 ID가 없거나 중복됩니다.");
            }
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
