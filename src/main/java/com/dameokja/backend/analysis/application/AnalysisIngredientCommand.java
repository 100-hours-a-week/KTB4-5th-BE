package com.dameokja.backend.analysis.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.create.IngredientCreateCommand;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AnalysisIngredientCommand(@NotBlank String analysisId, @NotEmpty @Size(max = 20) List<@NotNull @Valid Image> images) {
    public record Image(@NotBlank String imageObjectKey, @NotEmpty @Size(max = 20) List<@NotNull @Valid Item> items) {}

    public record Item(@NotBlank String itemId, @NotBlank String name, @NotNull IngredientCategory category,
            @NotNull StorageType storageType, @NotNull MeasureType measureType, BigDecimal quantity, @Valid Weight weight, @NotNull LocalDate expiration) {
        public IngredientCreateCommand toCommand(RegistrationSource source) {
            if (measureType == MeasureType.COUNT && weight != null) {
                throw new CustomException(IngredientExceptionCode.MIXED_MEASUREMENT);
            }
            Integer count;
            try {
                count = quantity == null ? null : quantity.intValueExact();
            } catch (ArithmeticException exception) {
                throw new CustomException(IngredientExceptionCode.INVALID_INPUT);
            }
            return new IngredientCreateCommand(name, category, storageType, measureType, count,
                    weight == null ? null : weight.value(), weight == null ? WeightUnit.NONE : weight.unit(), expiration, source);
        }
    }

    public record Weight(@NotNull BigDecimal value, @NotNull WeightUnit unit) {}
}
