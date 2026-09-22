package com.dameokja.backend.ingredient.application.expire;

import com.dameokja.backend.ingredient.domain.Ingredient;

public record IngredientExpireResult(Ingredient ingredient, boolean removed) {
}
