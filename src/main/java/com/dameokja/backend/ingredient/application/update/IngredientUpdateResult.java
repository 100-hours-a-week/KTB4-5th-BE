package com.dameokja.backend.ingredient.application.update;

import com.dameokja.backend.ingredient.domain.Ingredient;
import java.time.LocalDate;

public record IngredientUpdateResult(Ingredient ingredient, LocalDate businessDate) {
}
