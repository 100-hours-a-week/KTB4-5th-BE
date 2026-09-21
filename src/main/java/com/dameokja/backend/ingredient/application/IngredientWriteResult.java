package com.dameokja.backend.ingredient.application;

import java.util.List;

public record IngredientWriteResult(List<IngredientWriteItem> items, int createdCount,
        int mergedCount, long ingredientsNum, int capacity) {
}
