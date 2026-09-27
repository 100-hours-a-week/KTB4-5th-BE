package com.dameokja.backend.ingredient.application.create;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.Measurement;

public record IngredientWriteItem(Ingredient ingredient, boolean created,
        Measurement beforeMerge, Measurement addedByRequest) {

    public static IngredientWriteItem created(Ingredient ingredient) {
        return new IngredientWriteItem(ingredient, true, null, null);
    }

    public static IngredientWriteItem merged(Ingredient ingredient,
            Measurement beforeMerge, Measurement addedByRequest) {
        return new IngredientWriteItem(ingredient, false, beforeMerge, addedByRequest);
    }
}
