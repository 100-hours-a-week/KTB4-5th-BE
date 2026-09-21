package com.dameokja.backend.ingredient.presentation.request;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_INPUT;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.IngredientCreateCommand;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientCreateItem(
        @NotBlank String name, @NotNull IngredientCategory category, @NotNull StorageType storageType,
        @NotNull MeasureType measureType, BigDecimal quantity, BigDecimal weightValue, @NotNull WeightUnit weightUnit,
        @NotNull LocalDate expirationDate, @NotNull RegistrationSource registrationSource, @Null String imageUploadId
) {

    public IngredientCreateCommand toCommand() {
        Integer count = integerQuantity(quantity);
        return new IngredientCreateCommand(name, category, storageType, measureType,
                count, weightValue, weightUnit, expirationDate, registrationSource);
    }

    // 개수는 정수만 허용한다. 3.5개 같은 입력을 반올림하지 않고 거절하기 위해 여기서 확인한다.
    private static Integer integerQuantity(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw new CustomException(INVALID_INPUT);
        }
    }
}
