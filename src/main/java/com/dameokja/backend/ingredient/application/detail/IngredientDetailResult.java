package com.dameokja.backend.ingredient.application.detail;

import com.dameokja.backend.ingredient.domain.Ingredient;
import java.time.LocalDate;

public record IngredientDetailResult(Ingredient ingredient, LocalDate businessDate) {
}
