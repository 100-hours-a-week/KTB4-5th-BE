package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientCreateCommand(
        String name, IngredientCategory category, StorageType storageType,
        MeasureType measureType, Integer quantity, BigDecimal weightValue, WeightUnit weightUnit,
        LocalDate expirationDate, RegistrationSource registrationSource
) {
}
