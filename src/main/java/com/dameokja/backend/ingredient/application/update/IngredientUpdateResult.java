package com.dameokja.backend.ingredient.application.update;

import com.dameokja.backend.ingredient.application.create.IngredientWriteItem;
import com.dameokja.backend.ingredient.domain.Ingredient;
import java.time.LocalDate;
import java.util.List;

public record IngredientUpdateResult(Ingredient ingredient, LocalDate businessDate,
        List<IngredientWriteItem> mergedItems) {
}
