package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.create.IngredientWriteItem;
import com.dameokja.backend.ingredient.application.create.IngredientWriteResult;
import java.util.List;

public record IngredientCreateResponse(int createdCount, int mergedCount, long ingredientsNum,
        int refrigeratorCapacity, List<IngredientMergedItemResponse> mergedItems) {

    public static IngredientCreateResponse from(IngredientWriteResult result) {
        List<IngredientMergedItemResponse> mergedItems = result.items().stream()
                .filter(item -> !item.created())
                .map(IngredientMergedItemResponse::from)
                .toList();

        return new IngredientCreateResponse(result.createdCount(), result.mergedCount(),
                result.ingredientsNum(), result.capacity(), mergedItems);
    }
}
