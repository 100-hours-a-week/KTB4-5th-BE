package com.dameokja.backend.ingredient.presentation.request;

import java.util.List;

public record IngredientBulkExpireRequest(
        List<Long> ingredientIds
) {
}
