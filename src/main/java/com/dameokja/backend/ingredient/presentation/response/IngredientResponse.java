package com.dameokja.backend.ingredient.presentation.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.dameokja.backend.ingredient.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public record IngredientResponse(String ingredientId, String name, IngredientCategory category,
        StorageType storageType, MeasureType measureType, Short quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal weightValue, WeightUnit weightUnit,
        LocalDate expirationDate, LocalDate createdDate, RegistrationSource registrationSource,
        String imageUrl, String status, long daysUntilExpiration) {
    private static final int EXPIRING_SOON_DAYS = 3;

    public static IngredientResponse of(Ingredient ingredient, LocalDate today) {
        Measurement measurement = ingredient.getMeasurement();
        long days = ChronoUnit.DAYS.between(today, ingredient.getExpirationDate());
        LocalDate createdDate = ingredient.getCreatedAt().atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();
        return new IngredientResponse(ingredient.getId().toString(), ingredient.getName(),
                ingredient.getCategory(), ingredient.getStorageType(), measurement.getMeasureType(),
                measurement.getQuantity(), measurement.getWeightValue(), measurement.getWeightUnit(),
                ingredient.getExpirationDate(), createdDate, ingredient.getRegistrationSource(),
                null, status(days), days);
    }

    private static String status(long days) {
        if (days < 0) {
            return "EXPIRED";
        }
        return days <= EXPIRING_SOON_DAYS ? "EXPIRING_SOON" : "NORMAL";
    }
}
