package com.dameokja.backend.ingredient.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IngredientStatusTest {

    @ParameterizedTest
    @CsvSource({
            "-30, EXPIRED",
            "-1, EXPIRED",
            "0, EXPIRING_SOON",
            "3, EXPIRING_SOON",
            "4, NORMAL",
            "30, NORMAL"
    })
    void classifiesByDaysUntilExpiration(long daysUntilExpiration, IngredientStatus expected) {
        assertThat(IngredientStatus.of(daysUntilExpiration)).isEqualTo(expected);
    }
}
