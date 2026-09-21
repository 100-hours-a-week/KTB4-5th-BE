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
}
