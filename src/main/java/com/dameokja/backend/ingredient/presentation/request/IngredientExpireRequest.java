package com.dameokja.backend.ingredient.presentation.request;

import java.math.BigDecimal;

public record IngredientExpireRequest(
        Integer quantity,
        BigDecimal weightValue
) {
}
