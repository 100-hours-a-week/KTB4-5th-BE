package com.dameokja.backend.ingredient.application;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IngredientDetailServiceTest {
    @Mock private RefrigeratorAccessService accessService;
    @Mock private IngredientRepository ingredientRepository;
    private IngredientDetailService ingredientDetailService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T15:30:00Z"), ZoneOffset.UTC);
        ingredientDetailService = new IngredientDetailService(accessService, ingredientRepository, clock);
    }

    @Test
    void returnsIngredientAfterValidatingItsRefrigeratorAccess() {
        Ingredient ingredient = ingredient();
        when(ingredientRepository.findById(1L)).thenReturn(Optional.of(ingredient));

        IngredientDetailResult result = ingredientDetailService.getDetail(2L, 1L);

        assertThat(result.ingredient()).isSameAs(ingredient);
        assertThat(result.businessDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        verify(accessService).validateReadAccess(2L, 10L);
    }

    @Test
    void rejectsMissingIngredient() {
        when(ingredientRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingredientDetailService.getDetail(2L, 1L))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(NOT_FOUND));
    }

    private Ingredient ingredient() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        IngredientDetails details = new IngredientDetails(
                "두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.WEIGHT, null, new BigDecimal("300"), WeightUnit.G),
                LocalDate.of(2026, 9, 15));
        Ingredient ingredient = new Ingredient(refrigerator, details, RegistrationSource.RECEIPT);
        ReflectionTestUtils.setField(ingredient, "id", 1L);
        return ingredient;
    }
}
