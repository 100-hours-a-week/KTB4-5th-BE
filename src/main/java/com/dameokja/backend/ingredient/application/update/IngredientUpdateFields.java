package com.dameokja.backend.ingredient.application.update;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientUpdateFields(
        UpdateField<String> name,
        UpdateField<IngredientCategory> category,
        UpdateField<StorageType> storageType,
        UpdateField<Integer> quantity,
        UpdateField<BigDecimal> weightValue,
        UpdateField<WeightUnit> weightUnit,
        UpdateField<LocalDate> expirationDate) {

    public record UpdateField<T>(boolean provided, T value) {

        public static <T> UpdateField<T> omitted() {
            return new UpdateField<>(false, null);
        }

        public static <T> UpdateField<T> provided(T value) {
            return new UpdateField<>(true, value);
        }
    }
}
