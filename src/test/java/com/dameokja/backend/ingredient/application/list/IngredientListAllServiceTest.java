package com.dameokja.backend.ingredient.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;

class IngredientSnapshotServiceTest extends MySqlJpaTest {
    @Autowired private IngredientRepository ingredientRepository;
    private final RefrigeratorAccessService access = mock(RefrigeratorAccessService.class);
    private IngredientSnapshotService snapshots;
    private Refrigerator refrigerator;
    private User owner;

    @BeforeEach
    void setUp() {
        snapshots = new IngredientSnapshotService(access, ingredientRepository);
        refrigerator = new Refrigerator("냉장고", "2026-10");
        owner = new User("등록자", "test");
        entityManager.persist(refrigerator);
        entityManager.persist(owner);
    }

    @Test
    void readsAllStockIncludingExpiredAndFrozenRowsAndRemainsUsableAfterTransactionEnds() {
        LocalDate expiredOn = LocalDate.of(2026, 1, 1);
        Ingredient count = persist(refrigerator, "국산 두부", StorageType.FROZEN,
                Measurement.of(MeasureType.COUNT, 2, null, WeightUnit.NONE), expiredOn);
        Ingredient weight = persist(refrigerator, "김치", StorageType.REFRIGERATED,
                Measurement.of(MeasureType.WEIGHT, null, new BigDecimal("300"), WeightUnit.G), expiredOn.plusYears(1));
        populateOtherStock(expiredOn);
        Long refrigeratorId = refrigerator.getId();
        when(access.validateReadAccess(owner.getId(), refrigeratorId)).thenReturn(refrigerator);
        flushAndClear();

        List<IngredientSnapshotItem> inventory = snapshots.getAll(owner.getId(), refrigeratorId);
        TestTransaction.end();

        assertThat(inventory).hasSize(102).extracting(IngredientSnapshotItem::ingredientId).contains(count.getId(), weight.getId());
        assertThat(inventory.getFirst()).isEqualTo(new IngredientSnapshotItem(count.getId(), "국산 두부", IngredientCategory.OTHER,
                StorageType.FROZEN, MeasureType.COUNT, 2, null, WeightUnit.NONE, expiredOn));
        assertThat(inventory.get(1).weightValue()).isEqualByComparingTo("300");
        assertThat(inventory).extracting(IngredientSnapshotItem::name).doesNotContain("다른 재고");
    }

    @Test
    void rejectsReadingStockWhenAccessWasRevoked() {
        when(access.validateReadAccess(owner.getId(), refrigerator.getId()))
                .thenThrow(new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED));

        assertThatThrownBy(() -> snapshots.getAll(owner.getId(), refrigerator.getId()))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(RefrigeratorExceptionCode.ACCESS_DENIED));
    }

    @Test
    void returnsAnEmptySnapshotForAnAccessibleEmptyRefrigerator() {
        when(access.validateReadAccess(owner.getId(), refrigerator.getId())).thenReturn(refrigerator);

        assertThat(snapshots.getAll(owner.getId(), refrigerator.getId())).isEmpty();
    }

    private void populateOtherStock(LocalDate expirationDate) {
        for (int index = 0; index < 100; index++) {
            persist(refrigerator, "재고" + index, StorageType.REFRIGERATED,
                    Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), expirationDate);
        }
        Refrigerator other = new Refrigerator("다른 냉장고", "2026-10");
        entityManager.persist(other);
        persist(other, "다른 재고", StorageType.REFRIGERATED, Measurement.of(MeasureType.COUNT, 1, null, WeightUnit.NONE), expirationDate);
    }

    private Ingredient persist(Refrigerator target, String name, StorageType storageType, Measurement measurement, LocalDate expirationDate) {
        Ingredient ingredient = new Ingredient(target, new IngredientDetails(name, IngredientCategory.OTHER, storageType, measurement, expirationDate),
                RegistrationSource.DIRECT, owner);
        entityManager.persist(ingredient);
        return ingredient;
    }
}
