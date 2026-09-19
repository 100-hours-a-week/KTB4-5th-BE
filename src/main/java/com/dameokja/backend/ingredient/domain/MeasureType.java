package com.dameokja.backend.ingredient.domain;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import java.math.BigDecimal;

public enum MeasureType {

    COUNT {
        @Override
        Measurement measure(Integer quantity, BigDecimal weightValue, WeightUnit weightUnit) {
            if (weightValue != null || weightUnit != WeightUnit.NONE) {
                throw new CustomException(IngredientExceptionCode.MIXED_MEASUREMENT);
            }
            if (quantity == null || quantity < MIN_COUNT || quantity > MAX_COUNT) {
                throw new CustomException(IngredientExceptionCode.INVALID_AMOUNT);
            }
            return Measurement.count(quantity.shortValue());
        }
    },
    WEIGHT {
        @Override
        Measurement measure(Integer quantity, BigDecimal weightValue, WeightUnit weightUnit) {
            if (quantity != null || weightUnit == null || weightUnit == WeightUnit.NONE) {
                throw new CustomException(IngredientExceptionCode.MIXED_MEASUREMENT);
            }
            if (!isValidWeight(weightValue)) {
                throw new CustomException(IngredientExceptionCode.INVALID_AMOUNT);
            }
            return Measurement.weight(weightValue, weightUnit);
        }
    };

    static final int MIN_COUNT = 1;
    static final int MAX_COUNT = 100;
    static final int WEIGHT_SCALE = 3;
    static final BigDecimal MAX_WEIGHT = new BigDecimal("50000");

    abstract Measurement measure(Integer quantity, BigDecimal weightValue, WeightUnit weightUnit);

    private static boolean isValidWeight(BigDecimal weightValue) {
        return weightValue != null
                && weightValue.signum() > 0
                && weightValue.compareTo(MAX_WEIGHT) <= 0
                && weightValue.stripTrailingZeros().scale() <= WEIGHT_SCALE;
    }
}
