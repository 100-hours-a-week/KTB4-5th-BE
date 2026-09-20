package com.dameokja.backend.ingredient.domain;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Measurement {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MeasureType measureType;

    private Short quantity;

    @Column(precision = 10, scale = 3)
    private BigDecimal weightValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WeightUnit weightUnit;

    private Measurement(MeasureType measureType, Short quantity, BigDecimal weightValue, WeightUnit weightUnit) {
        this.measureType = measureType;
        this.quantity = quantity;
        this.weightValue = weightValue;
        this.weightUnit = weightUnit;
    }

    public static Measurement of(MeasureType measureType, Integer quantity,
                                 BigDecimal weightValue, WeightUnit weightUnit) {
        if (measureType == null) {
            throw new CustomException(IngredientExceptionCode.MIXED_MEASUREMENT);
        }
        return measureType.measure(quantity, weightValue, weightUnit);
    }

    static Measurement count(Short quantity) {
        return new Measurement(MeasureType.COUNT, quantity, null, WeightUnit.NONE);
    }

    static Measurement weight(BigDecimal weightValue, WeightUnit weightUnit) {
        // 스케일을 맞춰야 "300"과 "300.000"이 이후 합산 비교에서 같은 값으로 취급된다.
        return new Measurement(MeasureType.WEIGHT, null,
                weightValue.setScale(MeasureType.WEIGHT_SCALE), weightUnit);
    }

    public Measurement add(Measurement addition) {
        if (measureType != addition.measureType || weightUnit != addition.weightUnit) {
            throw new CustomException(IngredientExceptionCode.MIXED_MEASUREMENT);
        }
        Integer totalQuantity = quantity == null ? null : quantity + addition.quantity;
        BigDecimal totalWeight = weightValue == null ? null : weightValue.add(addition.weightValue);
        try {
            return of(measureType, totalQuantity, totalWeight, weightUnit);
        } catch (CustomException exception) {
            throw new CustomException(IngredientExceptionCode.MERGE_LIMIT_EXCEEDED);
        }
    }
}
