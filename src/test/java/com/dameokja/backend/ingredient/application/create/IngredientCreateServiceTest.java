package com.dameokja.backend.ingredient.application.create;

import com.dameokja.backend.ingredient.application.IngredientPolicy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngredientCreateServiceTest {
    @Mock private RefrigeratorAccessService access;
    @Mock private IngredientRepository repository;
    @Mock private ProhibitedWordChecker prohibitedWords;
    private final Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
    private IngredientCreateService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refrigerator, "id", 10L);
        Clock clock = Clock.fixed(Instant.parse("2026-09-19T15:00:00Z"),
                ZoneId.of("Asia/Seoul"));
        service = new IngredientCreateService(access, repository,
                new IngredientPolicy(prohibitedWords), clock);
        when(access.lockForWrite(1L, 10L)).thenReturn(refrigerator);
    }

    @Test
    void createsNewIngredientInLastSlot() {
        when(repository.countByRefrigeratorId(10L)).thenReturn(99L);
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        IngredientWriteResult result = service.create(1L, 10L, List.of(command(30)));
        IngredientWriteItem createdItem = result.items().getFirst();

        assertThat(createdItem.created()).isTrue();
        assertThat(result.ingredientsNum()).isEqualTo(100);
        assertThat(createdItem.ingredient().getMeasurement().getQuantity()).isEqualTo((short) 30);
    }

    @Test
    void capacityFailureDoesNotSave() {
        when(repository.countByRefrigeratorId(10L)).thenReturn(100L);
        assertThatThrownBy(() -> service.create(1L, 10L, List.of(command(1))))
                .isInstanceOf(CustomException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void mergesAutomaticallyAndReturnsMeasurementChanges() {
        Ingredient existing = candidate(60);
        IngredientWriteResult result = service.create(1L, 10L, List.of(command(40)));

        IngredientWriteItem mergedItem = result.items().getFirst();
        assertThat(result.mergedCount()).isEqualTo(1);
        assertThat(mergedItem.beforeMerge().getQuantity()).isEqualTo((short) 60);
        assertThat(mergedItem.addedByRequest().getQuantity()).isEqualTo((short) 40);
        assertThat(existing.getMeasurement().getQuantity()).isEqualTo((short) 100);
    }

    @Test
    void automaticMergePreservesOriginalMetadataWithoutUsingSlot() {
        Ingredient existing = candidate(60);
        IngredientWriteResult result = service.create(1L, 10L, List.of(command(40)));
        assertThat(result.items().getFirst().created()).isFalse();
        assertThat(existing.getMeasurement().getQuantity()).isEqualTo((short) 100);
        assertThat(existing.getRegistrationSource()).isEqualTo(RegistrationSource.RECEIPT);
        assertThat(existing.getCategory()).isEqualTo(IngredientCategory.OTHER);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void mergeAboveLimitLeavesOriginalAmountUnchanged() {
        Ingredient existing = candidate(60);
        assertThatThrownBy(() -> service.create(1L, 10L, List.of(command(41))))
                .isInstanceOf(CustomException.class);
        assertThat(existing.getMeasurement().getQuantity()).isEqualTo((short) 60);
    }

    private Ingredient candidate(int quantity) {
        Ingredient existing = new Ingredient(refrigerator, details(quantity),
                RegistrationSource.RECEIPT);
        ReflectionTestUtils.setField(existing, "id", 20L);
        when(repository.findMergeCandidates(eq(10L), any())).thenReturn(List.of(existing));
        return existing;
    }

    private IngredientCreateCommand command(int quantity) {
        return new IngredientCreateCommand("달걀", IngredientCategory.OTHER, StorageType.REFRIGERATED,
                MeasureType.COUNT, quantity, null, WeightUnit.NONE,
                LocalDate.of(2026, 9, 21), RegistrationSource.DIRECT);
    }

    private IngredientDetails details(int quantity) {
        return new IngredientDetails("달걀", IngredientCategory.OTHER, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, quantity, null, WeightUnit.NONE),
                LocalDate.of(2026, 9, 21));
    }
}
