package com.dameokja.backend.ingredient.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MeasurementTest {

    @ParameterizedTest
    @EnumSource(MeasureType.class)
    void rejectsZeroAmountWhenCreatingInventory(MeasureType measureType) {
        Integer quantity = measureType == MeasureType.COUNT ? 0 : null;
        BigDecimal weight = measureType == MeasureType.WEIGHT ? BigDecimal.ZERO : null;
        WeightUnit unit = measureType == MeasureType.WEIGHT ? WeightUnit.G : WeightUnit.NONE;

        assertThatThrownBy(() -> Measurement.of(measureType, quantity, weight, unit))
                .isInstanceOf(CustomException.class).extracting("exceptionCode")
                .isEqualTo(IngredientExceptionCode.INVALID_AMOUNT);
    }

    @Test
    void acceptsIntegerWeightWithDecimalScale() {
        Measurement measurement = Measurement.of(
                MeasureType.WEIGHT, null, new BigDecimal("150.000"), WeightUnit.G);

        assertThat(measurement.getWeightValue()).isEqualByComparingTo("150.000");
    }

    @Test
    void rejectsFractionalWeight() {
        assertThatThrownBy(() -> Measurement.of(
                MeasureType.WEIGHT, null, new BigDecimal("150.5"), WeightUnit.G))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void deductsPartOfCount() {
        Measurement measurement = Measurement.of(MeasureType.COUNT, 10, null, WeightUnit.NONE);
        Measurement deduction = Measurement.of(MeasureType.COUNT, 4, null, WeightUnit.NONE);

        boolean removed = measurement.subtract(deduction);

        assertThat(removed).isFalse();
        assertThat(measurement.getQuantity()).isEqualTo((short) 6);
    }

    @Test
    void reportsRemovalWhenAllWeightIsDeducted() {
        Measurement measurement = Measurement.of(
                MeasureType.WEIGHT, null, new BigDecimal("300"), WeightUnit.G);
        Measurement deduction = Measurement.of(
                MeasureType.WEIGHT, null, new BigDecimal("300"), WeightUnit.G);

        boolean removed = measurement.subtract(deduction);

        assertThat(removed).isTrue();
    }

    @Test
    void rejectsDeductionLargerThanStock() {
        Measurement measurement = Measurement.of(MeasureType.COUNT, 3, null, WeightUnit.NONE);
        Measurement deduction = Measurement.of(MeasureType.COUNT, 4, null, WeightUnit.NONE);

        assertThatThrownBy(() -> measurement.subtract(deduction))
                .isInstanceOf(CustomException.class);
    }
}
