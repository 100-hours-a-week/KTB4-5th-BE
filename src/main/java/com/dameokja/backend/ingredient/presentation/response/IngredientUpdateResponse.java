package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.application.update.IngredientUpdateResult;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

// 기존 수정 응답 필드는 그대로 펼쳐 두고, 합쳐진 재고 정보만 재고 생성과 같은 형식으로 덧붙인다.
public record IngredientUpdateResponse(
        @JsonUnwrapped IngredientResponse ingredient,
        List<IngredientMergedItemResponse> mergedItems) {

    public static IngredientUpdateResponse from(IngredientUpdateResult result) {
        IngredientResponse ingredient = IngredientResponse.of(result.ingredient(), result.businessDate());
        List<IngredientMergedItemResponse> mergedItems = result.mergedItems().stream()
                .map(IngredientMergedItemResponse::from)
                .toList();
        return new IngredientUpdateResponse(ingredient, mergedItems);
    }
}
