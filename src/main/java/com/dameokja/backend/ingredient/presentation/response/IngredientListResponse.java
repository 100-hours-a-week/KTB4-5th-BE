package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.list.IngredientListResult;
import java.util.List;

public record IngredientListResponse(
        long ingredientsNum,
        long filteredCount,
        Short refrigeratorCapacity,
        List<IngredientListItemResponse> ingredients,
        String nextCursor) {

    public static IngredientListResponse from(IngredientListResult result) {
        List<IngredientListItemResponse> ingredients = result.ingredients().stream()
                .map(ingredient -> IngredientListItemResponse.of(ingredient, result.businessDate()))
                .toList();
        return new IngredientListResponse(result.ingredientsNum(), result.filteredCount(),
                result.refrigeratorCapacity(), ingredients, result.nextCursor());
    }
}
