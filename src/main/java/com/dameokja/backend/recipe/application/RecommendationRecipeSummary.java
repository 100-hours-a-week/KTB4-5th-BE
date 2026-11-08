package com.dameokja.backend.recipe.application;

import com.dameokja.backend.ingredient.domain.IngredientStatus;
import com.dameokja.backend.recipe.application.RecommendationListResult.IngredientCounts;
import com.dameokja.backend.recipe.application.RecommendationListResult.RecipeSummary;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Allocation;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.InventoryUsage;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class RecommendationRecipeSummary {
    private RecommendationRecipeSummary() {}

    static RecipeSummary from(Recipe recipe, Map<Long, AiRecipeInventoryItem> inventory, LocalDate today) {
        return new RecipeSummary(recipe.source() + ":" + recipe.sourceRecipeId(), recipe.name(), recipe.imageUrl(), null, counts(recipe, inventory, today));
    }

    private static IngredientCounts counts(Recipe recipe, Map<Long, AiRecipeInventoryItem> inventory, LocalDate today) {
        if (recipe.ingredients() == null || recipe.ingredients().stream().anyMatch(ingredient -> ingredient.ingredientNo() == null)) {
            return new IngredientCounts(null, null, null);
        }
        Set<Integer> requiredIngredientNos = recipe.ingredients().stream().map(ingredient -> ingredient.ingredientNo()).collect(Collectors.toSet());
        if (recipe.inventoryUsage() == null) { return new IngredientCounts(requiredIngredientNos.size(), null, null); }
        Set<Integer> ownedIngredientNos = new HashSet<>();
        Set<Integer> expiringIngredientNos = new HashSet<>();
        for (InventoryUsage inventoryUsage : recipe.inventoryUsage()) {
            if ("UNUSED".equals(inventoryUsage.allocationStatus())) { continue; }
            if (inventoryUsage.allocations() == null || ("UNRESOLVED".equals(inventoryUsage.allocationStatus()) && inventoryUsage.allocations().isEmpty())) {
                return new IngredientCounts(requiredIngredientNos.size(), null, null);
            }
            AiRecipeInventoryItem stock = inventory.get(inventoryUsage.ingredientId());
            if (stock == null || !available(stock)) { continue; }
            for (Allocation allocation : inventoryUsage.allocations()) {
                if (!requiredIngredientNos.contains(allocation.ingredientNo())) { return new IngredientCounts(requiredIngredientNos.size(), null, null); }
                ownedIngredientNos.add(allocation.ingredientNo());
                if (!stock.expirationDate().isBefore(today) && !stock.expirationDate().isAfter(today.plusDays(IngredientStatus.EXPIRING_SOON_DAYS))) {
                    expiringIngredientNos.add(allocation.ingredientNo());
                }
            }
        }
        return new IngredientCounts(requiredIngredientNos.size(), ownedIngredientNos.size(), expiringIngredientNos.size());
    }

    private static boolean available(AiRecipeInventoryItem stock) {
        return stock.quantity() != null ? stock.quantity() > 0 : stock.weightValue() != null && stock.weightValue().signum() > 0;
    }
}
