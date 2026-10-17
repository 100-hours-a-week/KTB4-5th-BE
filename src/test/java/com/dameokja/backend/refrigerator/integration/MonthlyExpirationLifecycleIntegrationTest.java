package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.expire.IngredientExpireService;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields.UpdateField;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateService;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.MonthlyExpirationService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MonthlyExpirationLifecycleIntegrationTest extends ServiceIntegrationTest {
    @Autowired MonthlyExpirationService counts;
    @Autowired IngredientRepository ingredients;
    @Autowired IngredientExpireService expire;
    @Autowired IngredientUpdateService updates;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void deletionBeforeBatchPreservesAllExpiredRowsAndRepeatedDeletionAddsNothing() {
        Fixture fixture = ownerFixture("owner");
        Ingredient first = ingredient(fixture, "달걀", "2026-09-16");
        Ingredient second = ingredient(fixture, "우유", "2026-09-16");
        expire.expire(fixture.userId(), first.getId(), IngredientEtag.of(first), 10, null);
        assertThat(saved(fixture).getExpiredCount()).isEqualTo(2);
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isEqualTo(2);

        expire.expireSelected(fixture.userId(), fixture.refrigeratorId(), List.of(first.getId(), second.getId()));
        expire.expireSelected(fixture.userId(), fixture.refrigeratorId(), List.of(first.getId(), second.getId()));
        counts.synchronize(fixture.refrigeratorId());
        assertThat(ingredients.count()).isZero();
        assertThat(saved(fixture).getExpiredCount()).isEqualTo(2);
    }

    @Test
    void partialDisposalKeepsCountAndUnexpiredDisposalDoesNotAdd() {
        Fixture fixture = ownerFixture("owner");
        Ingredient expired = ingredient(fixture, "달걀", "2026-09-16");
        Ingredient fresh = ingredient(fixture, "우유", "2026-09-17");
        expire.expire(fixture.userId(), expired.getId(), IngredientEtag.of(expired), 2, null);
        expire.expire(fixture.userId(), fresh.getId(), IngredientEtag.of(fresh), 10, null);
        assertThat(saved(fixture).getExpiredCount()).isOne();
        assertThat(ingredients.findById(expired.getId()).orElseThrow().getMeasurement().getQuantity()).isEqualTo((short) 8);
    }

    @Test
    void monthRolloverCountsLastDayExpirationInNewMonthAndKeepsDeletedHistoryOnlyInOldMonth() {
        Fixture fixture = ownerFixture("owner");
        Ingredient old = ingredient(fixture, "달걀", "2026-09-16");
        ingredient(fixture, "우유", "2026-09-30");
        expire.expireSelected(fixture.userId(), fixture.refrigeratorId(), List.of(old.getId()));
        clock.set("2026-09-30T14:59:59Z");
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isOne();
        clock.set("2026-09-30T15:00:00Z");
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isOne();
        assertThat(saved(fixture).getExpiredCountMonth()).isEqualTo("2026-09");
        counts.synchronize(fixture.refrigeratorId());
        assertThat(saved(fixture).getExpiredCountMonth()).isEqualTo("2026-10");
        assertThat(saved(fixture).getExpiredCount()).isOne();
        clock.set("2026-10-31T15:00:00Z");
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isZero();
        counts.synchronize(fixture.refrigeratorId());
        assertThat(saved(fixture).getExpiredCount()).isZero();
    }

    @Test
    void concurrentBatchAndDeletionCountOnce() throws Exception {
        Fixture fixture = ownerFixture("owner");
        Ingredient ingredient = ingredient(fixture, "달걀", "2026-09-16");
        List<Object> results = concurrently(List.of(
                () -> {
                    counts.synchronize(fixture.refrigeratorId());
                    return true;
                },
                () -> {
                    counts.synchronize(fixture.refrigeratorId());
                    return true;
                },
                () -> {
                    expire.expireSelected(fixture.userId(), fixture.refrigeratorId(), List.of(ingredient.getId()));
                    return true;
                }));
        assertThat(results).containsOnly(true);
        assertThat(saved(fixture).getExpiredCount()).isOne();
        assertThat(ingredients.count()).isZero();
    }

    @Test
    void rollbackRestoresCounterDateAndDeletedIngredient() {
        Fixture fixture = ownerFixture("owner");
        Ingredient ingredient = ingredient(fixture, "달걀", "2026-09-16");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            expire.expireSelected(fixture.userId(), fixture.refrigeratorId(), List.of(ingredient.getId()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(saved(fixture).getExpiredCount()).isZero();
        assertThat(saved(fixture).getExpiredCountedOn()).isNull();
        assertThat(ingredients.existsById(ingredient.getId())).isTrue();
        counts.synchronize(fixture.refrigeratorId());
        assertThat(saved(fixture).getExpiredCount()).isOne();
    }

    @Test
    void rejectsExpiredEditsAndAllowsExpirationDayEdits() {
        Fixture fixture = ownerFixture("owner");
        Ingredient expired = ingredient(fixture, "만료", "2026-09-16");
        Ingredient today = ingredient(fixture, "오늘", "2026-09-17");
        IngredientUpdateFields fields = new IngredientUpdateFields(UpdateField.provided("수정"), UpdateField.omitted(),
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted());
        assertThatThrownBy(() -> updates.update(fixture.userId(), expired.getId(), IngredientEtag.of(expired), fields))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getExceptionCode().getCode()).isEqualTo("INGREDIENT-422-004"));
        updates.update(fixture.userId(), today.getId(), IngredientEtag.of(today), fields);
        assertThat(ingredients.findById(expired.getId()).orElseThrow().getName()).isEqualTo("만료");
        assertThat(ingredients.findById(today.getId()).orElseThrow().getName()).isEqualTo("수정");
    }

    private Ingredient ingredient(Fixture fixture, String name, String expiration) {
        IngredientDetails details = new IngredientDetails(name, IngredientCategory.OTHER, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 10, null, WeightUnit.NONE), LocalDate.parse(expiration));
        return ingredients.saveAndFlush(new Ingredient(saved(fixture), details, RegistrationSource.DIRECT));
    }

    private Refrigerator saved(Fixture fixture) {
        return refrigeratorRepository.findById(fixture.refrigeratorId()).orElseThrow();
    }
}
