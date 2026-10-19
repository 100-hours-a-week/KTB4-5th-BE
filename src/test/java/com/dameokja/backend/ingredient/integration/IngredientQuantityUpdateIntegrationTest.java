package com.dameokja.backend.ingredient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields.UpdateField;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateResult;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateService;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class IngredientQuantityUpdateIntegrationTest extends ServiceIntegrationTest {
    @Autowired IngredientUpdateService updates;
    @Autowired IngredientRepository ingredients;
    @Autowired MembershipService memberships;
    @Autowired InvitationService invitations;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void cleanQuantityFixtures() {
        // 커밋된 잠금 검증 데이터가 같은 MySQL을 쓰는 뒤 테스트에 섞이지 않게 한다.
        cleanDatabaseAndResetTime();
    }

    @Test
    void quantityUpdateLocksUserAndIngredientButNotRefrigeratorOrMembership() throws Exception {
        Fixture owner = ownerFixture("owner");
        Ingredient ingredient = ingredient(owner);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> pending = pool.submit(() -> holdQuantityUpdate(owner, ingredient, updated, release));
            await(updated);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbcTemplate.queryForList("SELECT refrigerator_id FROM refrigerators WHERE refrigerator_id=? FOR UPDATE NOWAIT",
                        owner.refrigeratorId());
                jdbcTemplate.queryForList("SELECT refrigerator_member_id FROM refrigerator_members WHERE user_id=? FOR UPDATE NOWAIT",
                        owner.userId());
            });
            assertLocked("SELECT user_id FROM users WHERE user_id=? FOR UPDATE NOWAIT", owner.userId());
            assertLocked("SELECT ingredient_id FROM ingredients WHERE ingredient_id=? FOR UPDATE NOWAIT", ingredient.getId());
            release.countDown();
            pending.get(15, TimeUnit.SECONDS);
            assertThat(ingredients.findById(ingredient.getId()).orElseThrow().getMeasurement().getQuantity().intValue()).isEqualTo(7);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void competingQuantityUpdatesRejectStaleEtag() throws Exception {
        Fixture owner = ownerFixture("owner");
        Fixture member = sharedMember(owner);
        Ingredient ingredient = ingredient(owner);
        String etag = IngredientEtag.of(ingredient);

        List<Object> results = concurrently(List.of(
                () -> updates.update(owner.userId(), ingredient.getId(), etag, quantity(7)),
                () -> updates.update(member.userId(), ingredient.getId(), etag, quantity(8))));

        assertThat(results.stream().filter(IngredientUpdateResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(CustomException.class::isInstance).map(CustomException.class::cast)
                .map(CustomException::getExceptionCode)).containsExactly(IngredientExceptionCode.STALE_VERSION);
    }

    @Test
    void updateWaitingForKickReadsLatestPermissionAndIsDenied() throws Exception {
        Fixture owner = ownerFixture("owner");
        Fixture member = sharedMember(owner);
        Ingredient ingredient = ingredient(owner);
        CountDownLatch kicked = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Answer<?> query = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            if (kicked.getCount() == 0) { attempted.countDown(); }
            return query.answer(invocation);
        }).when(userRepository).findByIdForUpdate(member.userId());
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> kick = pool.submit(() -> holdKick(owner, member, kicked, release));
            await(kicked);
            Future<Object> update = pool.submit(() -> updateOrError(member, ingredient));
            await(attempted);
            release.countDown();
            kick.get(15, TimeUnit.SECONDS);
            assertThat(update.get(15, TimeUnit.SECONDS)).isEqualTo(RefrigeratorExceptionCode.ACCESS_DENIED);
            assertThat(ingredients.findById(ingredient.getId()).orElseThrow().getMeasurement().getQuantity().intValue()).isEqualTo(10);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void quantityUpdateStillRejectsExpiredIngredient() {
        Fixture owner = ownerFixture("owner");
        Ingredient ingredient = ingredient(owner);
        clock.set("2026-09-25T15:00:00Z");

        assertThatThrownBy(() -> updates.update(owner.userId(), ingredient.getId(), IngredientEtag.of(ingredient), quantity(7)))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getExceptionCode()).isEqualTo(IngredientExceptionCode.INVALID_UPDATE_VALUE));
        assertThat(ingredients.findById(ingredient.getId()).orElseThrow().getMeasurement().getQuantity().intValue()).isEqualTo(10);
    }

    private void holdQuantityUpdate(Fixture owner, Ingredient ingredient, CountDownLatch updated, CountDownLatch release) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            updates.update(owner.userId(), ingredient.getId(), IngredientEtag.of(ingredient), quantity(7));
            updated.countDown();
            await(release);
        });
    }

    private void holdKick(Fixture owner, Fixture member, CountDownLatch kicked, CountDownLatch release) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            memberships.kick(owner.userId(), owner.refrigeratorId(), member.userId());
            kicked.countDown();
            await(release);
        });
    }

    private Object updateOrError(Fixture member, Ingredient ingredient) {
        try {
            return updates.update(member.userId(), ingredient.getId(), IngredientEtag.of(ingredient), quantity(7));
        } catch (CustomException error) {
            return error.getExceptionCode();
        }
    }

    private void assertLocked(String query, Long id) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> jdbcTemplate.queryForList(query, id)))
                .isInstanceOfSatisfying(DataAccessException.class,
                        error -> assertThat(((SQLException) error.getMostSpecificCause()).getErrorCode()).isEqualTo(3572));
    }

    private Fixture sharedMember(Fixture owner) {
        Fixture member = ownerFixture("member");
        memberships.join(member.userId(), invitations.issueCode(owner.userId(), owner.refrigeratorId()).code());
        return member;
    }

    private Ingredient ingredient(Fixture owner) {
        IngredientDetails details = new IngredientDetails("달걀", IngredientCategory.OTHER, StorageType.REFRIGERATED,
                Measurement.of(MeasureType.COUNT, 10, null, WeightUnit.NONE), LocalDate.of(2026, 9, 25));
        return ingredients.saveAndFlush(new Ingredient(refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow(),
                details, RegistrationSource.DIRECT, userRepository.findById(owner.userId()).orElseThrow()));
    }

    private IngredientUpdateFields quantity(int quantity) {
        return new IngredientUpdateFields(UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.provided(quantity), UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted());
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
