package com.dameokja.backend.ingredient.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MeasurementTest {

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
