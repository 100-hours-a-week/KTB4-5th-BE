package com.dameokja.backend.ingredient.presentation.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientStatus;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public record IngredientResponse(
        String ingredientId,
        String name,
        IngredientCategory category,
        StorageType storageType,
        MeasureType measureType,
        Short quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal weightValue,
        WeightUnit weightUnit,
        LocalDate expirationDate,
        LocalDate createdDate,
        RegistrationSource registrationSource,
        IngredientStatus status,
        long daysUntilExpiration) {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    public static IngredientResponse of(Ingredient ingredient, LocalDate today) {
        Measurement measurement = ingredient.getMeasurement();
        long days = ChronoUnit.DAYS.between(today, ingredient.getExpirationDate());
        String ingredientId = ingredient.getId().toString();
        IngredientStatus ingredientStatus = IngredientStatus.of(days);
        LocalDate createdDate = createdDateOf(ingredient);

        return new IngredientResponse(
                ingredientId,
                ingredient.getName(),
                ingredient.getCategory(),
                ingredient.getStorageType(),
                measurement.getMeasureType(),
                measurement.getQuantity(),
                measurement.getWeightValue(),
                measurement.getWeightUnit(),
                ingredient.getExpirationDate(),
                createdDate,
                ingredient.getRegistrationSource(),
                ingredientStatus,
                days);
    }

    private static LocalDate createdDateOf(Ingredient ingredient) {
        return ingredient.getCreatedAt().atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(BUSINESS_ZONE)
                .toLocalDate();
    }
}
