package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.detail.IngredientDetailResult;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record IngredientDetailResponse(
        @JsonUnwrapped IngredientResponse ingredient,
        @Schema(description = "상세의 상태·D-day를 계산한 요청 시점의 KST 날짜", example = "2026-09-16")
        LocalDate baseDate) {

    public static IngredientDetailResponse from(IngredientDetailResult result) {
        return new IngredientDetailResponse(
                IngredientResponse.of(result.ingredient(), result.businessDate()), result.businessDate());
    }
}
