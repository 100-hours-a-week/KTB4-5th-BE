package com.dameokja.backend.ingredient.presentation.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.ingredient.application.create.IngredientWriteItem;
import com.dameokja.backend.ingredient.application.create.IngredientWriteResult;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IngredientCreateResponseTest {

    @Test
    void includesWeightMergeDetails() {
        Measurement previous = Measurement.of(MeasureType.WEIGHT, null, new BigDecimal("500"), WeightUnit.ML);
        Measurement added = Measurement.of(MeasureType.WEIGHT, null, new BigDecimal("200"), WeightUnit.ML);
        Ingredient ingredient = ingredient(previous);
        ingredient.mergeMeasurement(added);
        IngredientWriteItem item = IngredientWriteItem.merged(ingredient, previous, added);
        IngredientWriteResult result = new IngredientWriteResult(List.of(item), 0, 1, 1, 100);

        IngredientCreateResponse response = IngredientCreateResponse.from(result);

        IngredientMergedItemResponse mergedItem = response.mergedItems().getFirst();
        assertThat(mergedItem.previousWeightValue()).isEqualByComparingTo("500.000");
        assertThat(mergedItem.addedWeightValue()).isEqualByComparingTo("200.000");
        assertThat(mergedItem.totalWeightValue()).isEqualByComparingTo("700.000");
        assertThat(mergedItem.weightUnit()).isEqualTo(WeightUnit.ML);
    }

    private Ingredient ingredient(Measurement measurement) {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        IngredientDetails details = new IngredientDetails("우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED,
                measurement, LocalDate.of(2026, 9, 21));
        Ingredient ingredient = new Ingredient(refrigerator, details, RegistrationSource.DIRECT);
        ReflectionTestUtils.setField(ingredient, "id", 10L);
        return ingredient;
    }
}
