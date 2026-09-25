package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.create.IngredientWriteItem;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;

public record IngredientMergedItemResponse(Long ingredientId, String name, MeasureType measureType,
        Integer previousQuantity, Integer addedQuantity, Integer totalQuantity,
        BigDecimal previousWeightValue, BigDecimal addedWeightValue, BigDecimal totalWeightValue,
        WeightUnit weightUnit) {

    public static IngredientMergedItemResponse from(IngredientWriteItem item) {
        Ingredient ingredient = item.ingredient();
        Measurement previous = item.beforeMerge();
        Measurement added = item.addedByRequest();
        Measurement total = ingredient.getMeasurement();

        return new IngredientMergedItemResponse(ingredient.getId(), ingredient.getName(), total.getMeasureType(),
                quantity(previous), quantity(added), quantity(total),
                previous.getWeightValue(), added.getWeightValue(), total.getWeightValue(), total.getWeightUnit());
    }

    private static Integer quantity(Measurement measurement) {
        Short quantity = measurement.getQuantity();
        return quantity == null ? null : quantity.intValue();
    }
}
