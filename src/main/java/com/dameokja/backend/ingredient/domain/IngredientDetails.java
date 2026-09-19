package com.dameokja.backend.ingredient.domain;

import java.time.LocalDate;

public record IngredientDetails(
        String name,
        IngredientCategory category,
        StorageType storageType,
        Measurement measurement,
        LocalDate expirationDate
) {
}
