package com.dameokja.backend.recipe.application;

import com.dameokja.backend.ingredient.application.IngredientConsumptionService.ConsumedIngredient;
import java.time.LocalDateTime;
import java.util.List;

public record CookingApplied(Long cookingRecordId, Long userId, Long refrigeratorId, String recommendationId,
                             String recipeId, LocalDateTime cookedAt, List<ConsumedIngredient> consumedIngredients) {
}
