package com.dameokja.backend.ingredient.application.expire;

import com.dameokja.backend.user.domain.User;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.dameokja.backend.refrigerator.application.MonthlyExpirationService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IngredientExpireSelectedTest {
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 20);

    @Mock private RefrigeratorAccessService refrigeratorAccessService;
    @Mock private IngredientRepository ingredientRepository;
    @Mock private MonthlyExpirationService monthlyExpirationService;

    private IngredientExpireService service;
    private Refrigerator refrigerator;

    @BeforeEach
    void setUp() {
        // 2026-09-19T15:00Z = 2026-09-20 00:00 KST
        Clock clock = Clock.fixed(Instant.parse("2026-09-19T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new IngredientExpireService(refrigeratorAccessService, ingredientRepository, clock, monthlyExpirationService);
        refrigerator = new Refrigerator("냉장고", "2026-09");
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
    }

    @Test
    void deletesSelectedExpiredIngredientsWithoutCountingDeletionAgain() {
        List<Ingredient> expired = List.of(ingredient("두유"), ingredient("애호박"));
        when(refrigeratorAccessService.lockForWrite(2L, 10L)).thenReturn(refrigerator);
        when(ingredientRepository.findByRefrigerator_IdAndIdInAndExpirationDateBefore(
                10L, List.of(1L, 2L, 3L), BUSINESS_DATE)).thenReturn(expired);

        int deletedCount = service.expireSelected(2L, 10L, List.of(1L, 2L, 3L, 1L));

        assertThat(deletedCount).isEqualTo(2);
        assertThat(refrigerator.getExpiredCount()).isZero();
        verify(monthlyExpirationService).accountPending(refrigerator);
        verify(ingredientRepository).deleteAll(expired);
    }

    @Test
    void rejectsEmptySelectionBeforeLocking() {
        assertThatThrownBy(() -> service.expireSelected(2L, 10L, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getExceptionCode().getCode())
                .isEqualTo("INGREDIENT-400-003");
        verifyNoInteractions(refrigeratorAccessService, ingredientRepository);
    }

    @Test
    void allowsSelectingAll1500Slots() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 1500).boxed().toList();
        when(refrigeratorAccessService.lockForWrite(2L, 10L)).thenReturn(refrigerator);

        assertThat(service.expireSelected(2L, 10L, ids)).isZero();
        verify(ingredientRepository).findByRefrigerator_IdAndIdInAndExpirationDateBefore(10L, ids, BUSINESS_DATE);
    }

    @Test
    void rejects1501DistinctSelectionsBeforeLocking() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 1501).boxed().toList();

        assertThatThrownBy(() -> service.expireSelected(2L, 10L, ids))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getExceptionCode().getCode())
                .isEqualTo("INGREDIENT-400-003");
        verifyNoInteractions(refrigeratorAccessService, ingredientRepository);
    }

    private Ingredient ingredient(String name) {
        IngredientDetails details = new IngredientDetails(name, IngredientCategory.OTHER, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), BUSINESS_DATE.minusDays(1));
        return new Ingredient(refrigerator, details, RegistrationSource.DIRECT, new User("등록자", "test"));
    }
}
