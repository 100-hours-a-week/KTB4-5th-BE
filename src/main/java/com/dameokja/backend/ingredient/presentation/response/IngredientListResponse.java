package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.list.IngredientListResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record IngredientListResponse(
        @Schema(description = "목록의 상태·D-day·필터를 계산한 첫 조회 날짜(KST)", example = "2026-09-16")
        LocalDate baseDate,
        @Schema(description = "기준일이 요청 시점의 KST 날짜보다 이전이면 true")
        boolean outdated,
        long ingredientsNum,
        long filteredCount,
        Short refrigeratorCapacity,
        List<IngredientListItemResponse> ingredients,
        String nextCursor) {

    public static IngredientListResponse from(IngredientListResult result) {
        List<IngredientListItemResponse> ingredients = result.ingredients().stream()
                .map(ingredient -> IngredientListItemResponse.of(ingredient, result.businessDate()))
                .toList();
        return new IngredientListResponse(result.businessDate(), result.outdated(), result.ingredientsNum(), result.filteredCount(),
                result.refrigeratorCapacity(), ingredients, result.nextCursor());
    }
}
