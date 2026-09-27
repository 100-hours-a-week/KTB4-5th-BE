package com.dameokja.backend.ingredient.application.expire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.ingredient.application.update.IngredientEtag;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IngredientExpireServiceTest {
    @Mock private RefrigeratorAccessService refrigeratorAccessService;
    @Mock private IngredientRepository ingredientRepository;

    private IngredientExpireService service;
    private Refrigerator refrigerator;

    @BeforeEach
    void setUp() {
        service = new IngredientExpireService(refrigeratorAccessService, ingredientRepository, Clock.systemUTC());
        refrigerator = new Refrigerator("냉장고", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        when(refrigeratorAccessService.lockCurrentForWrite(2L)).thenReturn(refrigerator);
    }

    @Test
    void keepsIngredientWhenQuantityRemains() {
        Ingredient ingredient = countIngredient(10);
        when(ingredientRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ingredient));

        Optional<IngredientExpireResult> result = service.expire(
                2L, 1L, IngredientEtag.of(ingredient), 4, null);

        assertThat(result).hasValueSatisfying(value -> assertThat(value.removed()).isFalse());
        assertThat(ingredient.getMeasurement().getQuantity()).isEqualTo((short) 6);
        assertThat(refrigerator.getExpiredCount()).isZero();
        verify(ingredientRepository, never()).delete(ingredient);
        verify(ingredientRepository).flush();
    }

    @Test
    void deletesIngredientAndCountsExpirationWhenNothingRemains() {
        Ingredient ingredient = countIngredient(10);
        when(ingredientRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(ingredient));

        Optional<IngredientExpireResult> result = service.expire(
                2L, 1L, IngredientEtag.of(ingredient), 10, null);

        assertThat(result).hasValueSatisfying(value -> assertThat(value.removed()).isTrue());
        assertThat(refrigerator.getExpiredCount()).isEqualTo(1);
        assertThat(refrigerator.getExpiredCountMonth()).isEqualTo("2026-09");
        verify(ingredientRepository).delete(ingredient);
        verify(ingredientRepository).flush();
    }

    @Test
    void returnsEmptyWhenIngredientWasAlreadyDeleted() {
        when(ingredientRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        Optional<IngredientExpireResult> result = service.expire(2L, 1L, "\"etag\"", 1, null);

        assertThat(result).isEmpty();
        assertThat(refrigerator.getExpiredCount()).isZero();
        verify(ingredientRepository, never()).flush();
    }

    private Ingredient countIngredient(int quantity) {
        IngredientDetails details = new IngredientDetails(
                "달걀", IngredientCategory.OTHER, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, quantity, null, WeightUnit.NONE),
                LocalDate.of(2026, 9, 15));
        Ingredient ingredient = new Ingredient(refrigerator, details, RegistrationSource.DIRECT);
        ReflectionTestUtils.setField(ingredient, "id", 1L);
        ReflectionTestUtils.setField(ingredient, "updatedAt", LocalDateTime.of(2026, 9, 16, 0, 0));
        return ingredient;
    }
}
