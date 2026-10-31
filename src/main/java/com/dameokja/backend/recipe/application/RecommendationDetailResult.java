package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.recipe.application.RecommendationStockAllocator.StockAllocation;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecommendationDetailResult(String recipeId, String name, String imageUrl, Integer cookingTimeMinutes, Integer expiringIngredientCount,
        Integer targetServings, List<RecipeIngredient> ingredients, List<Step> steps) {
    public SuccessResponse<RecommendationDetailResult> toResponse() { return SuccessResponse.of("RECOMMEND-200-002", "레시피 상세 조회 성공", this); }
    @Builder
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecipeIngredient(String recipeIngredientId, String name, String amountText, BigDecimal requiredAmount, String unit, List<Stock> stocks) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Stock(String ingredientId, BigDecimal ownedAmount, BigDecimal usedAmount, String unit, LocalDate expirationDate, String allocationStatus) {
        static Stock from(StockAllocation allocation) {
            return new Stock(allocation.ingredientId().toString(), allocation.ownedAmount(), allocation.usedAmount(), allocation.unit(), allocation.expirationDate(), allocation.allocationStatus());
        }
    }
    public record Step(Integer stepNo, String instruction) {}
}
