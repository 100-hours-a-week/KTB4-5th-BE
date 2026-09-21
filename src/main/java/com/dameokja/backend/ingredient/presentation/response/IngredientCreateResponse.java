package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.IngredientWriteResult;
import com.dameokja.backend.ingredient.application.IngredientWriteItem;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.util.List;

public record IngredientCreateResponse(int createdCount, int mergedCount, long ingredientsNum,
        int refrigeratorCapacity, List<MergedItem> mergedItems) {

    public static IngredientCreateResponse from(IngredientWriteResult result) {
        List<MergedItem> mergedItems = result.items().stream()
                .filter(item -> !item.created())
                .map(MergedItem::from)
                .toList();

        return new IngredientCreateResponse(result.createdCount(), result.mergedCount(),
                result.ingredientsNum(), result.capacity(), mergedItems);
    }

    public record MergedItem(Long ingredientId, String name, MeasureType measureType,
            Integer previousQuantity, Integer addedQuantity, Integer totalQuantity,
            BigDecimal previousWeightValue, BigDecimal addedWeightValue, BigDecimal totalWeightValue,
            WeightUnit weightUnit) {

        private static MergedItem from(IngredientWriteItem item) {
            Ingredient ingredient = item.ingredient();
            Measurement previous = item.beforeMerge();
            Measurement added = item.addedByRequest();
            Measurement total = ingredient.getMeasurement();

            return new MergedItem(ingredient.getId(), ingredient.getName(), total.getMeasureType(),
                    quantity(previous), quantity(added), quantity(total),
                    previous.getWeightValue(), added.getWeightValue(), total.getWeightValue(), total.getWeightUnit());
        }

        private static Integer quantity(Measurement measurement) {
            Short quantity = measurement.getQuantity();
            return quantity == null ? null : quantity.intValue();
        }
    }
}
