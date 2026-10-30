package com.dameokja.backend.recipe.infrastructure.ai;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiRecipeInventoryItem(Long ingredientId, String name, IngredientCategory category, StorageType storageType,
        MeasureType measureType, Integer quantity, BigDecimal weightValue, WeightUnit weightUnit, LocalDate expirationDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) String ingredientKey) {
    public AiRecipeInventoryItem(Long ingredientId, String name, IngredientCategory category, StorageType storageType,
            MeasureType measureType, Integer quantity, BigDecimal weightValue, WeightUnit weightUnit, LocalDate expirationDate) {
        this(ingredientId, name, category, storageType, measureType, quantity, weightValue, weightUnit, expirationDate, null);
    }

    public static AiRecipeInventoryItem from(Ingredient ingredient) {
        Measurement measurement = ingredient.getMeasurement();
        return new AiRecipeInventoryItem(ingredient.getId(), ingredient.getName(), ingredient.getCategory(), ingredient.getStorageType(),
                measurement.getMeasureType(), measurement.getQuantity() == null ? null : measurement.getQuantity().intValue(),
                measurement.getWeightValue(), measurement.getWeightUnit(), ingredient.getExpirationDate());
    }
}
