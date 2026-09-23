package com.dameokja.backend.ingredient.application;

import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IngredientPolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);
    private final IngredientPolicy ingredientPolicy = new IngredientPolicy(mock(ProhibitedWordChecker.class));

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "' \t대파 한단\n'|대파 한단",
            "'　 대파 한단  '|대파 한단",
            "'대파'|대파"
    })
    void trimsUnicodeEdgeWhitespaceKeepsInnerSpaceAndComposesHangul(String name, String expected) {
        IngredientDetails validated = ingredientPolicy.validateForCreate(details(name), TODAY);
        assertThat(validated.name()).isEqualTo(expected);
    }

    private IngredientDetails details(String name) {
        return new IngredientDetails(name, IngredientCategory.VEGETABLE, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), TODAY);
    }
}
