package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.domain.Ingredient;
import java.time.LocalDate;
import java.util.List;

public record IngredientListResult(
        List<Ingredient> ingredients,
        LocalDate businessDate,
        long ingredientsNum,
        long filteredCount,
        Short refrigeratorCapacity,
        String nextCursor) {
}
