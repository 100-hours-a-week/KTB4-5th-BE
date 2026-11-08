package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.application.RecommendationDetailResult.RecipeIngredient;
import com.dameokja.backend.recipe.application.RecommendationDetailResult.Step;
import com.dameokja.backend.recipe.application.RecommendationDetailResult.Stock;
import com.dameokja.backend.recipe.application.RecommendationStockAllocator.StockAllocation;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Ingredient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.IngredientRequirement;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class RecommendationDetailMapper {
    private RecommendationDetailMapper() {}

    static RecommendationDetailResult from(Recipe recipe, Map<Long, AiRecipeInventoryItem> inventoryById, LocalDate today) {
        RecommendationListResult.RecipeSummary summary = RecommendationRecipeSummary.from(recipe, inventoryById, today);
        Map<Integer, List<StockAllocation>> allocations = RecommendationStockAllocator.allocate(recipe, inventoryById);
        return RecommendationDetailResult.builder()
                .recipeId(summary.recipeId())
                .name(recipe.name())
                .imageUrl(recipe.imageUrl())
                .cookingTimeMinutes(summary.cookingTimeMinutes())
                .expiringIngredientCount(summary.ingredientCounts().expiring())
                .targetServings(recipe.targetServings())
                .ingredients(ingredients(recipe, allocations))
                .steps(steps(recipe))
                .build();
    }

    private static List<RecipeIngredient> ingredients(Recipe recipe, Map<Integer, List<StockAllocation>> allocations) {
        if (recipe.ingredients() == null) { return List.of(); }
        return recipe.ingredients().stream().map(ingredient -> ingredient(recipe, ingredient, allocations.getOrDefault(ingredient.ingredientNo(), List.of()))).toList();
    }

    private static RecipeIngredient ingredient(Recipe recipe, Ingredient ingredient, List<StockAllocation> allocations) {
        RecipeIngredient.RecipeIngredientBuilder ingredientBuilder = RecipeIngredient.builder()
                .name(ingredient.name())
                .amountText(ingredient.amountText())
                .unit(RecommendationStockAllocator.normalizedUnit(ingredient.unit()))
                .stocks(allocations.stream().map(Stock::from).toList());
        if (ingredient.ingredientNo() != null) { ingredientBuilder.recipeIngredientId(ingredient.ingredientNo().toString()); }
        IngredientRequirement requirement = RecommendationStockAllocator.requirement(recipe, ingredient.ingredientNo());
        if (requirement != null) {
            ingredientBuilder.requiredAmount(requirement.requiredAmount());
            ingredientBuilder.unit(RecommendationStockAllocator.normalizedUnit(requirement.unit()));
            if (requirement.amountText() != null) { ingredientBuilder.amountText(requirement.amountText()); }
        }
        return ingredientBuilder.build();
    }

    private static List<Step> steps(Recipe recipe) {
        if (recipe.steps() == null) { return List.of(); }
        return recipe.steps().stream().map(step -> new Step(step.stepNo(), step.instruction())).toList();
    }
}
