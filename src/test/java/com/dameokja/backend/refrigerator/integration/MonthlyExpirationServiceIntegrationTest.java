package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
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
import com.dameokja.backend.refrigerator.application.MonthlyExpirationService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class MonthlyExpirationServiceIntegrationTest extends ServiceIntegrationTest {
    @Autowired MonthlyExpirationService counts;
    @Autowired IngredientRepository ingredients;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void expirationAtMidnightIsVisibleWithoutBatchOrDeletionAndGetDoesNotWrite() {
        Fixture fixture = ownerFixture("owner");
        Ingredient ingredient = ingredient(fixture, "달걀", "2026-09-17");
        String etag = IngredientEtag.of(ingredient);
        clock.set("2026-09-17T14:59:59Z");
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isZero();
        clock.set("2026-09-17T15:00:00Z");
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isOne();
        assertThat(saved(fixture).getExpiredCountedOn()).isNull();

        counts.synchronize(fixture.refrigeratorId());
        counts.synchronize(fixture.refrigeratorId());
        assertThat(saved(fixture).getExpiredCount()).isOne();
        assertThat(counts.getCount(fixture.userId(), fixture.refrigeratorId())).isOne();
        assertThat(IngredientEtag.of(ingredients.findById(ingredient.getId()).orElseThrow())).isEqualTo(etag);
    }

    @Test
    void catchesUpOnlyCurrentMonthAfterDowntime() {
        Fixture fixture = ownerFixture("owner");
        ingredient(fixture, "예전", "2026-09-28");
        ingredient(fixture, "월초", "2026-09-30");
        ingredient(fixture, "현재", "2026-10-05");
        ingredient(fixture, "오늘", "2026-10-10");
        clock.set("2026-10-10T03:00:00Z");
        counts.synchronize(fixture.refrigeratorId());
        assertThat(saved(fixture).getExpiredCount()).isEqualTo(2);
    }

    @Test
    void deniesOtherRefrigeratorAndSkipsDeletedRefrigeratorInBatch() {
        Fixture first = ownerFixture("first");
        Fixture second = ownerFixture("second");
        assertThatThrownBy(() -> counts.getCount(first.userId(), second.refrigeratorId()))
                .isInstanceOf(CustomException.class);
        jdbcTemplate.update("update refrigerators set deleted_at = '2026-09-17 12:00:00' where refrigerator_id = ?", second.refrigeratorId());
        counts.synchronize(second.refrigeratorId());
        assertThat(saved(second).getExpiredCountedOn()).isNull();
        assertThat(counts.findPendingIds(0L, 100)).containsExactly(first.refrigeratorId());
    }

    @Test
    void batchLocksRefrigeratorWithoutLockingCountedIngredients() throws Exception {
        Fixture fixture = ownerFixture("owner");
        Ingredient ingredient = ingredient(fixture, "달걀", "2026-09-16");
        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> batch = pool.submit(() -> holdBatch(fixture, counted, release));
            awaitBatch(counted);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbcTemplate.queryForList(
                    "SELECT ingredient_id FROM ingredients WHERE ingredient_id=? FOR UPDATE NOWAIT", ingredient.getId()));
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbcTemplate.queryForList(
                    "SELECT refrigerator_id FROM refrigerators WHERE refrigerator_id=? FOR UPDATE NOWAIT", fixture.refrigeratorId())))
                    .isInstanceOfSatisfying(DataAccessException.class,
                            error -> assertThat(((SQLException) error.getMostSpecificCause()).getErrorCode()).isEqualTo(3572));
            release.countDown();
            batch.get(15, TimeUnit.SECONDS);
            assertThat(saved(fixture).getExpiredCount()).isOne();
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void newMonthCountsPreviousMonthLastDayAndResetsStoredTotal() {
        Fixture fixture = ownerFixture("owner");
        ingredient(fixture, "달걀", "2026-09-16");
        ingredient(fixture, "우유", "2026-09-30");
        counts.synchronize(fixture.refrigeratorId());
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
    void concurrentAccountingAndRepeatedCallsCountEachRowOnce() throws Exception {
        Fixture fixture = ownerFixture("owner");
        ingredient(fixture, "달걀", "2026-09-16");
        List<Object> results = concurrently(List.of(
                () -> {
                    counts.synchronize(fixture.refrigeratorId());
                    return true;
                },
                () -> {
                    counts.synchronize(fixture.refrigeratorId());
                    return true;
                }));
        assertThat(results).containsOnly(true);
        assertThat(saved(fixture).getExpiredCount()).isOne();
    }

    @Test
    void accountPendingRequiresCallerTransactionAndRollsBackWithIt() {
        Fixture fixture = ownerFixture("owner");
        ingredient(fixture, "달걀", "2026-09-16");
        assertThatThrownBy(() -> counts.accountPending(saved(fixture)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Refrigerator refrigerator = refrigeratorRepository.findByIdForUpdate(fixture.refrigeratorId()).orElseThrow();
            counts.accountPending(refrigerator);
            assertThat(refrigerator.getExpiredCount()).isOne();
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(saved(fixture).getExpiredCount()).isZero();
        assertThat(saved(fixture).getExpiredCountedOn()).isNull();
    }

    @Test
    void preDeletionAccountingUsesLatestRowsAfterEarlierSnapshot() {
        Fixture fixture = ownerFixture("owner");
        Ingredient ingredient = ingredient(fixture, "달걀", "2026-09-16");
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String countSql = "select count(*) from ingredients where refrigerator_id=? and expiration_date < '2026-09-17'";
            assertThat(jdbcTemplate.queryForObject(countSql, Integer.class, fixture.refrigeratorId())).isOne();
            independent.executeWithoutResult(inner -> jdbcTemplate.update(
                    "update ingredients set expiration_date='2026-09-18' where ingredient_id=?", ingredient.getId()));
            assertThat(jdbcTemplate.queryForObject(countSql, Integer.class, fixture.refrigeratorId())).isOne();
            Refrigerator refrigerator = refrigeratorRepository.findByIdForUpdate(fixture.refrigeratorId()).orElseThrow();
            counts.accountPending(refrigerator);
            assertThat(refrigerator.getExpiredCount()).isZero();
        });
        assertThat(saved(fixture).getExpiredCount()).isZero();
        assertThat(saved(fixture).getExpiredCountedOn()).isEqualTo(LocalDate.parse("2026-09-17"));
    }

    @Test
    void pendingIdsUseStableCursorAndExcludeAlreadyAccountedDate() {
        Fixture first = ownerFixture("first");
        Fixture second = ownerFixture("second");
        assertThat(counts.findPendingIds(0L, 1)).containsExactly(first.refrigeratorId());
        assertThat(counts.findPendingIds(first.refrigeratorId(), 1)).containsExactly(second.refrigeratorId());
        counts.synchronize(first.refrigeratorId());
        assertThat(counts.findPendingIds(0L, 100)).containsExactly(second.refrigeratorId());
        clock.set("2026-09-17T15:00:00Z");
        assertThat(counts.findPendingIds(0L, 100)).containsExactly(first.refrigeratorId(), second.refrigeratorId());
    }

    private void holdBatch(Fixture fixture, CountDownLatch counted, CountDownLatch release) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            counts.synchronize(fixture.refrigeratorId());
            counted.countDown();
            awaitBatch(release);
        });
    }

    private void awaitBatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
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
