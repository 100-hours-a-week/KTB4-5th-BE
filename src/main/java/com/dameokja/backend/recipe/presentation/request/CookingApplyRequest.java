package com.dameokja.backend.recipe.presentation.request;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.recipe.application.CookingApplicationRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CookingApplyRequest(
        @Schema(description = "표시한 추천 목록의 ID 문자열", example = "123")
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String recommendationId,
        @Schema(description = "상세 조회에서 받은 원천 조합 ID", example = "FOOD_SAFETY_KOREA:1024")
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*:.+") String recipeId) {
    private static final int MAX_KEY_LENGTH = 64;

    public CookingApplicationRequest toCommand(String ifMatch, String idempotencyKey) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9a-f]{64}\"")
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        try { return new CookingApplicationRequest(Long.valueOf(recommendationId), recipeId, ifMatch, idempotencyKey); }
        catch (NumberFormatException exception) { throw new CustomException(GlobalExceptionCode.BAD_REQUEST); }
    }
}
