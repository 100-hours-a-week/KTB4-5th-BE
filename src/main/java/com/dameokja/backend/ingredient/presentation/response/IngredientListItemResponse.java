package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientStatus;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record IngredientListItemResponse(
        String ingredientId,
        String name,
        IngredientCategory category,
        Short quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal weightValue,
        WeightUnit weightUnit,
        StorageType storageType,
        IngredientStatus status,
        long daysUntilExpiration) {

    public static IngredientListItemResponse of(Ingredient ingredient, LocalDate businessDate) {
        Measurement measurement = ingredient.getMeasurement();
        long days = ChronoUnit.DAYS.between(businessDate, ingredient.getExpirationDate());
        return new IngredientListItemResponse(
                ingredient.getId().toString(),
                ingredient.getName(),
                ingredient.getCategory(),
                measurement.getQuantity(),
                measurement.getWeightValue(),
                measurement.getWeightUnit(),
                ingredient.getStorageType(),
                IngredientStatus.of(days),
                days);
    }
}
