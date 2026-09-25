package com.dameokja.backend.ingredient.presentation.request;

import java.util.List;

public record IngredientExpireSelectedRequest(
        List<Long> ingredientIds
) {
}
