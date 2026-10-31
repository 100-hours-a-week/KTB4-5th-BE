package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CookingDetailSnapshotTest extends ServiceIntegrationTest {
    private static final String RECIPE_ID = "FOOD_SAFETY_KOREA:1024";
    @Autowired private RecommendationDetailService recommendationDetailService;
    @Autowired private RecommendationDetailEtag recommendationDetailEtag;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JsonMapper jsonMapper;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    private TransactionTemplate transactionTemplate;
    private Fixture refrigeratorParticipant;
    private Long stockId;
    private RecommendationList selectedList;

    @BeforeEach
    void prepareSelectedResult() throws Exception {
        transactionTemplate = new TransactionTemplate(transactionManager);
        refrigeratorParticipant = ownerFixture("적용회원");
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, measure_type, quantity, weight_value, weight_unit, expiration_date, registration_source)
                VALUES (?, ?, '국산 두부', 'TOFU_BEAN', 'WEIGHT', NULL, 400, 'G', ?, 'DIRECT')
                """, refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId(), BusinessTime.today(clock).plusDays(2));
        stockId = jdbcTemplate.queryForObject("SELECT ingredient_id FROM ingredients", Long.class);
        selectedList = saveList(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), "선택 요리");
        RecommendationState recommendationState = recommendationStateRepository.saveAndFlush(new RecommendationState(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId())));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ? WHERE recommendation_state_id = ?",
                selectedList.getId(), selectedList.getId(), recommendationState.getId());
    }

    @Test
    void buildsTheSameResponseAndEtagFromLockedCurrentInventory() {
        RecommendationDetailResult displayedDetail = recommendationDetailService.get(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), RECIPE_ID);
        RecommendationDetailResult lockedDetail = snapshot(selectedList.getId());

        assertThat(lockedDetail).isEqualTo(displayedDetail);
        assertThat(recommendationDetailEtag.of(lockedDetail)).isEqualTo(recommendationDetailEtag.of(displayedDetail));
        jdbcTemplate.update("UPDATE ingredients SET weight_value = 70 WHERE ingredient_id = ?", stockId);
        RecommendationDetailResult changedDetail = snapshot(selectedList.getId());
        assertThat(changedDetail.ingredients().getFirst().stocks().getFirst().usedAmount()).isEqualByComparingTo("70");
        assertThat(recommendationDetailEtag.of(changedDetail)).isNotEqualTo(recommendationDetailEtag.of(displayedDetail));
    }

    @Test
    void readsRequestedOwnedResultWithoutChangingCurrentSelection() throws Exception {
        RecommendationList requestedList = saveList(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), "요청 요리");

        assertThat(snapshot(requestedList.getId()).name()).isEqualTo("요청 요리");
        assertThat(recommendationDetailService.get(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), RECIPE_ID).name()).isEqualTo("선택 요리");
    }

    @Test
    void rejectsResultsBelongingToAnotherParticipantOrRefrigerator() throws Exception {
        Fixture otherParticipant = ownerFixture("다른적용회원");
        RecommendationList otherParticipantList = saveList(otherParticipant.userId(), refrigeratorParticipant.refrigeratorId(), "다른 참여자 요리");
        RecommendationList otherRefrigeratorList = saveList(refrigeratorParticipant.userId(), otherParticipant.refrigeratorId(), "다른 냉장고 요리");

        assertRecipeNotFound(otherParticipantList.getId());
        assertRecipeNotFound(otherRefrigeratorList.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsStockLockUntilTheCallingTransactionCommitsOrRollsBack(boolean rollBack) throws Exception {
        ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                recommendationDetailService.getForCooking(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), selectedList.getId(), RECIPE_ID);
                Future<Integer> conflictingUpdate = writerExecutor.submit(this::updateStockWithShortLockTimeout);
                assertThatThrownBy(() -> conflictingUpdate.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CannotAcquireLockException.class);
                if (rollBack) { transactionStatus.setRollbackOnly(); }
            });
            assertThat(jdbcTemplate.update("UPDATE ingredients SET weight_value = 50 WHERE ingredient_id = ?", stockId)).isEqualTo(1);
        } finally {
            writerExecutor.shutdownNow();
            assertThat(writerExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Integer updateStockWithShortLockTimeout() {
        return transactionTemplate.execute(transaction -> {
            Integer previousTimeout = jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
            jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
            try {
                return jdbcTemplate.update("UPDATE ingredients SET weight_value = 50 WHERE ingredient_id = ?", stockId);
            } finally {
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = " + previousTimeout);
            }
        });
    }

    private void assertRecipeNotFound(Long recommendationId) {
        assertThatThrownBy(() -> snapshot(recommendationId)).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(RecommendationExceptionCode.RECIPE_NOT_FOUND));
    }

    private RecommendationDetailResult snapshot(Long recommendationId) {
        return transactionTemplate.execute(transaction ->
                recommendationDetailService.getForCooking(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), recommendationId, RECIPE_ID));
    }

    private RecommendationList saveList(Long userId, Long refrigeratorId, String name) throws Exception {
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
        snapshot.put("userId", userId).put("refrigeratorId", refrigeratorId);
        ObjectNode recipe = (ObjectNode) snapshot.path("recommendations").get(0);
        recipe.put("name", name);
        ((ObjectNode) recipe.path("inventoryUsage").get(0)).put("ingredientId", stockId);
        return recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(userId),
                refrigeratorRepository.getReferenceById(refrigeratorId), UUID.randomUUID().toString(), snapshot.toString()));
    }
}
