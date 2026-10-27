package com.dameokja.backend.ingredient.application.snapshot;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientSnapshotItem(Long ingredientId, String name, IngredientCategory category, StorageType storageType,
        MeasureType measureType, Integer quantity, BigDecimal weightValue, WeightUnit weightUnit, LocalDate expirationDate) {
    public static IngredientSnapshotItem from(Ingredient ingredient) {
        Measurement measurement = ingredient.getMeasurement();
        return new IngredientSnapshotItem(ingredient.getId(), ingredient.getName(), ingredient.getCategory(), ingredient.getStorageType(),
                measurement.getMeasureType(), measurement.getQuantity() == null ? null : measurement.getQuantity().intValue(),
                measurement.getWeightValue(), measurement.getWeightUnit(), ingredient.getExpirationDate());
    }
}
