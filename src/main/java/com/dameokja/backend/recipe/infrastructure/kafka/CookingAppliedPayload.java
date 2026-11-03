package com.dameokja.backend.recipe.infrastructure.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CookingAppliedPayload(Instant occurredAt, String requestId, Long userId, Long refrigeratorId, Long cookingRecordId, String recommendationId,
        String recipeId, String recipeName, Instant cookedAt, List<Consumption> consumptions) {
    public CookingAppliedPayload {
        RecipeEventValidation.validate(occurredAt, requestId, userId, refrigeratorId);
        if (cookingRecordId == null || cookingRecordId <= 0 || recommendationId == null || recommendationId.isBlank() || recipeId == null || recipeId.isBlank()) {
            throw new IllegalArgumentException("요리 적용 이벤트의 이력·추천·레시피 식별자가 올바르지 않습니다.");
        }
        Objects.requireNonNull(cookedAt);
        Objects.requireNonNull(recipeName);
        consumptions = List.copyOf(consumptions);
    }

    public enum Unit { EA, G, ML }

    public record Consumption(Long ingredientId, BigDecimal usedAmount, Unit unit) {
        public Consumption {
            if (ingredientId == null || ingredientId <= 0 || usedAmount == null || usedAmount.signum() <= 0 || unit == null) {
                throw new IllegalArgumentException("실제 차감한 재고·수량·단위가 올바르지 않습니다.");
            }
        }
    }
}
