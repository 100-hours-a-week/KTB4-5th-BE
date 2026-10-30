package com.dameokja.backend.recipe.presentation.request;

import com.dameokja.backend.recipe.application.RecommendationOptions;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.FieldError;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

public record RecommendationJobSubmitRequest(
        @Schema(defaultValue = "EXCLUDE_EXPIRED", allowableValues = {"INCLUDE_EXPIRED", "EXCLUDE_EXPIRED"})
        @Pattern(regexp = "INCLUDE_EXPIRED|EXCLUDE_EXPIRED") String mode,
        @Schema(description = "취향 태그. 생략 시 []") @Size(max = 12) List<@NotBlank @CodePointLength(max = 40) String> preferenceTags,
        @Schema(defaultValue = "10") @Min(1) @Max(10) Integer limit,
        @Schema(defaultValue = "2") @Min(1) @Max(12) Integer targetServings,
        @Schema(description = "USE_EXPIRING / MINIMIZE_SHOPPING / SIMPLE_COOKING. 생략 시 모두 적용, USE_EXPIRING 항상 적용")
        @Size(min = 1, max = 3) List<@NotBlank @Pattern(regexp = "USE_EXPIRING|MINIMIZE_SHOPPING|SIMPLE_COOKING") String> strategies,
        @Schema(description = "제외 재료. 생략 시 []") @Size(max = 100) List<@NotBlank String> excludedIngredientNames,
        @Schema(description = "보유 양념. 생략 시 []") @Size(max = 30) List<@NotBlank @CodePointLength(max = 500) String> pantryIngredientNames,
        @Schema(description = "최근 노출된 SOURCE:sourceRecipeId. 생략 시 []")
        @Size(max = 100) List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*:.+") String> recentRecipeIds) {
    public RecommendationOptions toOptions() {
        if (strategies != null && new HashSet<>(strategies).size() != strategies.size()) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST, List.of(FieldError.of("BODY", "/strategies", "FORMAT", "추천 전략은 중복될 수 없습니다.")));
        }
        return RecommendationOptions.fromInput(mode, preferenceTags, limit, targetServings, strategies, excludedIngredientNames, pantryIngredientNames, recentRecipeIds);
    }
}
