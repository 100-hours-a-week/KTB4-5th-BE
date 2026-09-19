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
            if (!isStorableWeight(weightValue)) {
                throw new CustomException(IngredientExceptionCode.INVALID_AMOUNT);
            }
            return Measurement.weight(weightValue, weightUnit);
        }
    };

    static final int MIN_COUNT = 1;
    static final int MAX_COUNT = 100;
    static final int WEIGHT_SCALE = 3;
    // 가정용 냉장고에서 한 항목이 넘을 수 없는 값. 자릿수 실수를 거르면서 정상 입력은 막지 않는다.
    static final BigDecimal MAX_WEIGHT = new BigDecimal("50000");

    // 측정 방식마다 허용 필드와 범위가 달라서, 분기 대신 상수별 구현으로 규칙을 각자 갖는다.
    abstract Measurement measure(Integer quantity, BigDecimal weightValue, WeightUnit weightUnit);

    private static boolean isStorableWeight(BigDecimal weightValue) {
        return weightValue != null
                && weightValue.signum() > 0
                && weightValue.compareTo(MAX_WEIGHT) <= 0
                // 반올림하면 사용자가 입력하지 않은 값이 저장되므로 자릿수 초과는 거부한다.
                && weightValue.stripTrailingZeros().scale() <= WEIGHT_SCALE;
    }
}
