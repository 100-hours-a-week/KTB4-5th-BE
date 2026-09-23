package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import java.time.LocalDate;

public record IngredientListQuery(
        Long refrigeratorId,
        IngredientSortType sortType,
        LocalDate baseDate,
        IngredientCursor cursor,
        int limit) {
}
