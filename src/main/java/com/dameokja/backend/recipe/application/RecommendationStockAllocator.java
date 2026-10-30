package com.dameokja.backend.recipe.application;

import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Ingredient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.IngredientRequirement;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.InventoryUsage;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecommendationStockAllocator {
    private static final int WEIGHT_SCALE = 3;

    private RecommendationStockAllocator() {}

    static Map<Integer, List<StockAllocation>> allocate(Recipe recipe, Map<Long, AiRecipeInventoryItem> inventoryById) {
        Map<Integer, List<StockAllocation>> allocationsByIngredient = new LinkedHashMap<>();
        Map<Long, BigDecimal> remainingByStock = new HashMap<>();
        if (recipe.ingredients() == null) { return allocationsByIngredient; }
        for (Ingredient ingredient : recipe.ingredients()) {
            Integer ingredientNo = ingredient.ingredientNo();
            if (ingredientNo == null || allocationsByIngredient.containsKey(ingredientNo)) { continue; }
            IngredientRequirement requirement = requirement(recipe, ingredientNo);
            List<AiRecipeInventoryItem> mappedStocks = mappedStocks(recipe.inventoryUsage(), ingredientNo, inventoryById);
            List<StockAllocation> stockAllocations = allocateIngredient(requirement, mappedStocks, remainingByStock);
            allocationsByIngredient.put(ingredientNo, stockAllocations);
        }
        return allocationsByIngredient;
    }

    static IngredientRequirement requirement(Recipe recipe, Integer ingredientNo) {
        if (ingredientNo == null || recipe.ingredientRequirements() == null) { return null; }
        return recipe.ingredientRequirements().stream().filter(requirement -> ingredientNo.equals(requirement.ingredientNo())).findFirst().orElse(null);
    }

    private static List<AiRecipeInventoryItem> mappedStocks(List<InventoryUsage> inventoryUsage, Integer ingredientNo,
            Map<Long, AiRecipeInventoryItem> inventoryById) {
        Map<Long, AiRecipeInventoryItem> mappedStocks = new LinkedHashMap<>();
        if (inventoryUsage == null) { return List.of(); }
        for (InventoryUsage usage : inventoryUsage) {
            if ("UNUSED".equals(usage.allocationStatus()) || usage.allocations() == null) { continue; }
            boolean mapped = usage.allocations().stream().anyMatch(allocation -> ingredientNo.equals(allocation.ingredientNo()));
            AiRecipeInventoryItem stock = inventoryById.get(usage.ingredientId());
            if (mapped && stock != null && amount(stock).signum() > 0) { mappedStocks.putIfAbsent(stock.ingredientId(), stock); }
        }
        return List.copyOf(mappedStocks.values());
    }

    private static List<StockAllocation> allocateIngredient(IngredientRequirement requirement, List<AiRecipeInventoryItem> mappedStocks,
            Map<Long, BigDecimal> remainingByStock) {
        List<StockAllocation> allocations = new ArrayList<>();
        String requiredUnit = null;
        if (requirement != null) { requiredUnit = normalizedUnit(requirement.unit()); }
        BigDecimal remainingRequired = requiredAmount(requirement, requiredUnit);
        for (AiRecipeInventoryItem stock : mappedStocks) {
            BigDecimal usedAmount = null;
            if (remainingRequired != null && unit(stock).equals(requiredUnit)) {
                BigDecimal remainingStock = remainingByStock.getOrDefault(stock.ingredientId(), amount(stock));
                usedAmount = remainingRequired.min(remainingStock);
                remainingRequired = remainingRequired.subtract(usedAmount);
                remainingByStock.put(stock.ingredientId(), remainingStock.subtract(usedAmount));
            }
            allocations.add(StockAllocation.from(stock, usedAmount));
        }
        return List.copyOf(allocations);
    }

    private static BigDecimal requiredAmount(IngredientRequirement requirement, String requiredUnit) {
        if (requirement == null || requiredUnit == null || requirement.requiredAmount() == null || requirement.requiredAmount().signum() < 0) { return null; }
        int scale = WEIGHT_SCALE;
        if ("EA".equals(requiredUnit)) { scale = 0; }
        // 현재 재고가 표현할 수 있는 개수·소수 정밀도에서 필요량과 가장 가까운 값으로 맞춘다.
        return requirement.requiredAmount().setScale(scale, RoundingMode.HALF_UP);
    }

    static String normalizedUnit(String unit) {
        if (unit == null) { return null; }
        return switch (unit) {
            case "EA", "COUNT" -> "EA";
            case "G", "ML" -> unit;
            default -> null;
        };
    }

    private static BigDecimal amount(AiRecipeInventoryItem stock) {
        if (stock.measureType() == MeasureType.COUNT && stock.quantity() != null) { return BigDecimal.valueOf(stock.quantity()); }
        if (stock.weightValue() != null) { return stock.weightValue(); }
        return BigDecimal.ZERO;
    }

    private static String unit(AiRecipeInventoryItem stock) {
        if (stock.measureType() == MeasureType.COUNT) { return "EA"; }
        return stock.weightUnit().name();
    }

    record StockAllocation(Long ingredientId, BigDecimal ownedAmount, BigDecimal usedAmount, String unit, LocalDate expirationDate, String allocationStatus) {
        private static StockAllocation from(AiRecipeInventoryItem stock, BigDecimal usedAmount) {
            String allocationStatus = "CALCULATED";
            if (usedAmount == null) { allocationStatus = "UNRESOLVED"; }
            return new StockAllocation(stock.ingredientId(), amount(stock), usedAmount, RecommendationStockAllocator.unit(stock), stock.expirationDate(), allocationStatus);
        }
    }
}
