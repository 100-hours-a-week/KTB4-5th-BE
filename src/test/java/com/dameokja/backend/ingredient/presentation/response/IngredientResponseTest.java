package com.dameokja.backend.ingredient.presentation.response;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class IngredientResponseTest {
    @Test
    void usesStoredSeoulDateAsCreatedDate() {
        IngredientDetails details = new IngredientDetails("두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), LocalDate.of(2026, 9, 10));
        Ingredient ingredient = new Ingredient(new Refrigerator("냉장고", "2026-09"), details, RegistrationSource.DIRECT);
        ReflectionTestUtils.setField(ingredient, "id", 1L);
        ReflectionTestUtils.setField(ingredient, "createdAt", LocalDateTime.of(2026, 9, 1, 23, 30));

        IngredientResponse response = IngredientResponse.of(ingredient, LocalDate.of(2026, 9, 2));

        assertThat(response.createdDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    }
}
