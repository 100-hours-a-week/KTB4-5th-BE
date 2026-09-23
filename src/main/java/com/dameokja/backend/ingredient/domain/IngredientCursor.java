package com.dameokja.backend.ingredient.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record IngredientCursor(
        LocalDate expirationDate,
        LocalDateTime createdAt,
        String name,
        Long ingredientId) {

    public static IngredientCursor from(Ingredient ingredient) {
        return new IngredientCursor(ingredient.getExpirationDate(), ingredient.getCreatedAt(),
                ingredient.getName(), ingredient.getId());
    }
}
