package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.detail.IngredientDetailResult;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record IngredientDetailResponse(
        @JsonUnwrapped IngredientResponse ingredient,
        @Schema(description = "상세의 상태·D-day를 계산한 요청 시점의 KST 날짜", example = "2026-09-16")
        LocalDate baseDate,
        @Schema(description = "재고의 최초 등록 회원(owner_id)의 현재 닉네임", example = "등록자")
        String ownerNickname) {

    public static IngredientDetailResponse from(IngredientDetailResult result) {
        return new IngredientDetailResponse(
                IngredientResponse.of(result.ingredient(), result.businessDate()), result.businessDate(), result.ownerNickname());
    }
}
