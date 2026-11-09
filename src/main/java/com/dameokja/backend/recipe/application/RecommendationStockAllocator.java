package com.dameokja.backend.recipe.application;

import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Ingredient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Allocation;
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
    private RecommendationStockAllocator() {}

    static Map<Integer, List<StockAllocation>> allocate(Recipe recipe, Map<Long, AiRecipeInventoryItem> inventoryById) {
        Map<Integer, List<StockAllocation>> allocationsByIngredient = new LinkedHashMap<>();
        Map<Long, BigDecimal> remainingByStock = new HashMap<>();
        if (recipe.ingredients() == null) { return allocationsByIngredient; }
        for (Ingredient ingredient : recipe.ingredients()) {
            Integer ingredientNo = ingredient.ingredientNo();
            if (ingredientNo == null || allocationsByIngredient.containsKey(ingredientNo)) { continue; }
            IngredientRequirement requirement = requirement(recipe, ingredientNo);
            List<MappedStock> mappedStocks = mappedStocks(recipe.inventoryUsage(), ingredientNo, inventoryById);
            List<StockAllocation> stockAllocations = allocateIngredient(requirement, mappedStocks, remainingByStock);
            allocationsByIngredient.put(ingredientNo, stockAllocations);
        }
        return allocationsByIngredient;
    }

    static IngredientRequirement requirement(Recipe recipe, Integer ingredientNo) {
        if (ingredientNo == null || recipe.ingredientRequirements() == null) { return null; }
        return recipe.ingredientRequirements().stream().filter(requirement -> ingredientNo.equals(requirement.ingredientNo())).findFirst().orElse(null);
    }

    private static List<MappedStock> mappedStocks(List<InventoryUsage> inventoryUsage, Integer ingredientNo,
            Map<Long, AiRecipeInventoryItem> inventoryById) {
        Map<Long, MappedStock> mappedStocks = new LinkedHashMap<>();
        if (inventoryUsage == null) { return List.of(); }
        for (InventoryUsage usage : inventoryUsage) {
            if ("UNUSED".equals(usage.allocationStatus()) || usage.allocations() == null) { continue; }
            boolean mapped = usage.allocations().stream().anyMatch(allocation -> ingredientNo.equals(allocation.ingredientNo()));
            AiRecipeInventoryItem stock = inventoryById.get(usage.ingredientId());
            if (mapped && stock != null && amount(stock).signum() > 0) {
                mappedStocks.putIfAbsent(stock.ingredientId(), new MappedStock(stock, usage, plannedAmount(usage, ingredientNo)));
            }
        }
        return List.copyOf(mappedStocks.values());
    }

    private static List<StockAllocation> allocateIngredient(IngredientRequirement requirement, List<MappedStock> mappedStocks,
            Map<Long, BigDecimal> remainingByStock) {
        List<StockAllocation> allocations = new ArrayList<>();
        String requiredUnit = null;
        if (requirement != null) { requiredUnit = normalizedUnit(requirement.unit()); }
        BigDecimal remainingRequired = requiredAmount(requirement, requiredUnit);
        for (MappedStock mappedStock : mappedStocks) {
            AiRecipeInventoryItem stock = mappedStock.stock();
            BigDecimal remainingStock = remainingByStock.getOrDefault(stock.ingredientId(), amount(stock));
            BigDecimal usedAmount = usedAmount(mappedStock, remainingStock, remainingRequired, requiredUnit);
            if (usedAmount != null) {
                if (remainingRequired != null && unit(stock).equals(requiredUnit)) { remainingRequired = remainingRequired.subtract(usedAmount).max(BigDecimal.ZERO); }
                remainingByStock.put(stock.ingredientId(), remainingStock.subtract(usedAmount));
            }
            allocations.add(StockAllocation.from(stock, usedAmount));
        }
        return List.copyOf(allocations);
    }

    private static BigDecimal plannedAmount(InventoryUsage usage, Integer ingredientNo) {
        BigDecimal planned = BigDecimal.ZERO;
        for (Allocation allocation : usage.allocations()) {
            if (!ingredientNo.equals(allocation.ingredientNo())) { continue; }
            if (allocation.usedAmount() == null) {
                if (usage.allocations().size() == 1) { return usage.usedAmount(); }
                return null;
            }
            if (allocation.usedAmount().signum() < 0) { return allocation.usedAmount(); }
            planned = planned.add(allocation.usedAmount());
        }
        return planned;
    }

    private static BigDecimal usedAmount(MappedStock mapped, BigDecimal remainingStock, BigDecimal remainingRequired, String requiredUnit) {
        BigDecimal planned = mapped.plannedAmount();
        String plannedUnit = normalizedUnit(mapped.usage().unit());
        if (planned == null) {
            planned = remainingRequired;
            plannedUnit = requiredUnit;
        }
        if (planned == null || planned.signum() < 0 || !unit(mapped.stock()).equals(plannedUnit)) { return null; }
        planned = planned.setScale(0, RoundingMode.HALF_UP);
        BigDecimal total = mapped.usage().usedAmount();
        if (total != null) {
            if (total.signum() < 0 || !unit(mapped.stock()).equals(normalizedUnit(mapped.usage().unit()))) { return null; }
            BigDecimal consumed = amount(mapped.stock()).subtract(remainingStock);
            planned = planned.min(total.setScale(0, RoundingMode.HALF_UP).subtract(consumed).max(BigDecimal.ZERO));
        }
        return planned.min(remainingStock);
    }

    private static BigDecimal requiredAmount(IngredientRequirement requirement, String requiredUnit) {
        if (requirement == null || requiredUnit == null || requirement.requiredAmount() == null || requirement.requiredAmount().signum() < 0) { return null; }
        // 재고 등록·수정과 동일하게 개수·중량·용량을 모두 정수로 맞춘다.
        return requirement.requiredAmount().setScale(0, RoundingMode.HALF_UP);
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

    private record MappedStock(AiRecipeInventoryItem stock, InventoryUsage usage, BigDecimal plannedAmount) {}

    record StockAllocation(Long ingredientId, BigDecimal ownedAmount, BigDecimal usedAmount, String unit, LocalDate expirationDate, String allocationStatus) {
        private static StockAllocation from(AiRecipeInventoryItem stock, BigDecimal usedAmount) {
            String allocationStatus = "CALCULATED";
            if (usedAmount == null) { allocationStatus = "UNRESOLVED"; }
            return new StockAllocation(stock.ingredientId(), amount(stock), usedAmount, RecommendationStockAllocator.unit(stock), stock.expirationDate(), allocationStatus);
        }
    }
}
