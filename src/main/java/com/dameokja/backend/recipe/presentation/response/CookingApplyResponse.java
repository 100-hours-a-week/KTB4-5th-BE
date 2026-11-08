package com.dameokja.backend.recipe.presentation.response;

import com.dameokja.backend.recipe.application.RecommendationDetailResult;
import com.dameokja.backend.recipe.application.RecommendationDetailResult.RecipeIngredient;
import com.dameokja.backend.recipe.application.RecommendationDetailResult.Stock;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CookingApplyResponse(String recipeId, List<UsedIngredient> useIngredients) {
    public static CookingApplyResponse from(RecommendationDetailResult detail) {
        Map<String, UsedIngredient> usageByStock = new LinkedHashMap<>();
        for (RecipeIngredient ingredient : detail.ingredients()) {
            for (Stock stock : ingredient.stocks()) {
                if ("CALCULATED".equals(stock.allocationStatus()) && stock.usedAmount() != null && stock.usedAmount().signum() > 0) {
                    UsedIngredient usage = new UsedIngredient(stock.ingredientId(), stock.usedAmount(), stock.unit());
                    usageByStock.merge(stock.ingredientId(), usage, UsedIngredient::combine);
                }
            }
        }
        return new CookingApplyResponse(detail.recipeId(), List.copyOf(usageByStock.values()));
    }

    public record UsedIngredient(String ingredientId, BigDecimal useAmount, String unit) {
        private UsedIngredient combine(UsedIngredient other) { return new UsedIngredient(ingredientId, useAmount.add(other.useAmount), unit); }
    }
}
