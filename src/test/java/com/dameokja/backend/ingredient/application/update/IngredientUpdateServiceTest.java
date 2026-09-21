package com.dameokja.backend.ingredient.application.update;

import com.dameokja.backend.ingredient.application.IngredientPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields.UpdateField;
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
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IngredientUpdateServiceTest {
    @Mock private RefrigeratorAccessService accessService;
    @Mock private IngredientRepository ingredientRepository;
    @Mock private ProhibitedWordChecker prohibitedWordChecker;

    private IngredientUpdateService updateService;
    private Refrigerator refrigerator;
    private Ingredient ingredient;

    @BeforeEach
    void setUp() {
        refrigerator = new Refrigerator("냉장고", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        ingredient = ingredient(refrigerator, LocalDate.of(2026, 9, 15));

        when(ingredientRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(ingredient));

        Clock clock = Clock.fixed(Instant.parse("2026-09-19T15:00:00Z"),
                ZoneId.of("Asia/Seoul"));
        updateService = new IngredientUpdateService(accessService, ingredientRepository,
                new IngredientUpdatePolicy(new IngredientPolicy(prohibitedWordChecker)), clock);
    }

    @Test
    void updatesProvidedFieldsAndKeepsServerManagedFields() {
        IngredientUpdateFields fields = new IngredientUpdateFields(
                UpdateField.provided("연두부"), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.provided(null), UpdateField.provided(new BigDecimal("250")),
                UpdateField.provided(WeightUnit.G), UpdateField.omitted());

        IngredientUpdateResult result = updateService.update(
                1L, 20L, IngredientEtag.of(ingredient), fields);

        assertThat(result.ingredient().getName()).isEqualTo("연두부");
        assertThat(result.ingredient().getMeasurement().getWeightValue())
                .isEqualByComparingTo("250.000");
        assertThat(result.ingredient().getExpirationDate())
                .isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(result.ingredient().getRegistrationSource())
                .isEqualTo(RegistrationSource.RECEIPT);
        verify(accessService).validateWriteAccess(1L, 10L);
        verify(ingredientRepository).flush();
    }

    @Test
    void validatesExpirationOnlyWhenItActuallyChanges() {
        IngredientUpdateFields unchangedPastDate = fieldsWithExpiration(
                LocalDate.of(2026, 9, 15));

        updateService.update(1L, 20L, IngredientEtag.of(ingredient), unchangedPastDate);

        assertThatThrownBy(() -> updateService.update(1L, 20L,
                IngredientEtag.of(ingredient), fieldsWithExpiration(LocalDate.of(2026, 9, 19))))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void rejectsExplicitNullForUsedMeasurement() {
        IngredientUpdateFields fields = new IngredientUpdateFields(
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.omitted(), UpdateField.provided(null), UpdateField.omitted(),
                UpdateField.omitted());

        assertThatThrownBy(() -> updateService.update(
                1L, 20L, IngredientEtag.of(ingredient), fields))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getExceptionCode().getCode())
                .isEqualTo("INGREDIENT-422-002");
        verify(ingredientRepository, never()).flush();
    }

    @Test
    void rejectsChangingWeightUnitWhileConversionPolicyIsUnresolved() {
        IngredientUpdateFields fields = new IngredientUpdateFields(
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.provided(WeightUnit.ML),
                UpdateField.omitted());

        assertThatThrownBy(() -> updateService.update(
                1L, 20L, IngredientEtag.of(ingredient), fields))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getExceptionCode().getCode())
                .isEqualTo("INGREDIENT-422-004");
        verify(ingredientRepository, never()).flush();
    }

    @Test
    void rejectsStaleEtagBeforeChangingIngredient() {
        assertThatThrownBy(() -> updateService.update(
                1L, 20L, "\"stale\"", emptyFields()))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception)
                        .getExceptionCode().getCode())
                .isEqualTo("INGREDIENT-412-001");
        verify(ingredientRepository, never()).flush();
    }

    @Test
    void checksAccessUsingRefrigeratorOwnedByIngredient() {
        updateService.update(1L, 20L, IngredientEtag.of(ingredient), emptyFields());

        verify(accessService).validateWriteAccess(1L, 10L);
    }

    private IngredientUpdateFields fieldsWithExpiration(LocalDate expirationDate) {
        return new IngredientUpdateFields(
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.provided(expirationDate));
    }

    private IngredientUpdateFields emptyFields() {
        return new IngredientUpdateFields(
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.omitted());
    }

    private Ingredient ingredient(Refrigerator owner, LocalDate expirationDate) {
        IngredientDetails details = new IngredientDetails(
                "두부", IngredientCategory.TOFU_BEAN, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.WEIGHT, null, new BigDecimal("300"), WeightUnit.G),
                expirationDate);
        Ingredient result = new Ingredient(owner, details, RegistrationSource.RECEIPT);
        ReflectionTestUtils.setField(result, "id", 20L);
        return result;
    }
}
