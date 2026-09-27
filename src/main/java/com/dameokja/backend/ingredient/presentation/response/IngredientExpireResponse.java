package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.expire.IngredientExpireResult;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

public record IngredientExpireResponse(
        String ingredientId,
        boolean removed,
        Short remainingQuantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingWeightValue,
        WeightUnit weightUnit
) {
    public static IngredientExpireResponse from(IngredientExpireResult result) {
        Measurement measurement = result.ingredient().getMeasurement();
        Short quantity = result.removed() ? null : measurement.getQuantity();
        BigDecimal weightValue = result.removed() ? null : measurement.getWeightValue();
        return new IngredientExpireResponse(result.ingredient().getId().toString(), result.removed(),
                quantity, weightValue, measurement.getWeightUnit());
    }
}
