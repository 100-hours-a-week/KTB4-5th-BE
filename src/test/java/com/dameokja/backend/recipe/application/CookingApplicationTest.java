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
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CookingApplicationTest extends ServiceIntegrationTest {
    private static final String RECIPE_ID = "FOOD_SAFETY_KOREA:1024";
    @Autowired private CookingApplicationService cookingApplicationService;
    @Autowired private RecommendationDetailService recommendationDetailService;
    @Autowired private RecommendationDetailEtag recommendationDetailEtag;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    private Fixture refrigeratorParticipant;
    private Long stockId;
    private RecommendationList selectedList;

    @BeforeEach
    void prepareSelectedResult() throws Exception {
        refrigeratorParticipant = ownerFixture("조리적용회원");
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, measure_type, quantity, weight_value, weight_unit, expiration_date, registration_source)
                VALUES (?, ?, '국산 두부', 'TOFU_BEAN', 'WEIGHT', NULL, 400, 'G', ?, 'DIRECT')
                """, refrigeratorParticipant.refrigeratorId(), refrigeratorParticipant.userId(), BusinessTime.today(clock).plusDays(2));
        stockId = jdbcTemplate.queryForObject("SELECT ingredient_id FROM ingredients", Long.class);
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json"));
        snapshot.put("userId", refrigeratorParticipant.userId()).put("refrigeratorId", refrigeratorParticipant.refrigeratorId());
        ObjectNode recipe = (ObjectNode) snapshot.path("recommendations").get(0);
        ((ObjectNode) recipe.path("inventoryUsage").get(0)).put("ingredientId", stockId);
        ((ObjectNode) recipe.path("ingredientRequirements").get(0)).put("requiredAmount", new BigDecimal("300.125")).put("amountText", "300.125g");
        selectedList = recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId()), UUID.randomUUID().toString(), snapshot.toString()));
        RecommendationState recommendationState = recommendationStateRepository.saveAndFlush(new RecommendationState(userRepository.getReferenceById(refrigeratorParticipant.userId()),
                refrigeratorRepository.getReferenceById(refrigeratorParticipant.refrigeratorId())));
        jdbcTemplate.update("UPDATE recommendation_states SET current_list_id = ?, latest_list_id = ? WHERE recommendation_state_id = ?",
                selectedList.getId(), selectedList.getId(), recommendationState.getId());
    }

    @ParameterizedTest
    @CsvSource({"false,100", "true,1"})
    void appliesPartialAndFullConsumptionWithOneRecordAndCountPerCooking(boolean countStock, BigDecimal expectedRemaining) throws Exception {
        if (countStock) { useCountStock(); }
        assertThat(apply(request())).isPresent();
        assertThat(stockAmount()).isEqualByComparingTo(expectedRemaining);
        assertOutcome(1);
        assertThat(apply(request())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isZero();
        assertOutcome(2);
    }

    @Test
    void changedDetailFailsWithoutSideEffectsAndCanBeReconfirmed() {
        CookingApplicationRequest outdatedRequest = request();
        jdbcTemplate.update("UPDATE ingredients SET weight_value = 70 WHERE ingredient_id = ?", stockId);
        assertFailure(outdatedRequest, RecommendationExceptionCode.DETAIL_CHANGED);
        assertThat(stockAmount()).isEqualByComparingTo("70");
        assertOutcome(0);
        assertThat(apply(request())).isPresent();
        assertOutcome(1);
    }

    @Test
    void successfulRetryPrecedesCurrentEtagAndSnapshotChecksAndRejectsChangedRequests() {
        CookingApplicationRequest originalRequest = request();
        apply(originalRequest);
        jdbcTemplate.update("UPDATE ingredients SET weight_value = 25 WHERE ingredient_id = ?", stockId);
        jdbcTemplate.update("DELETE FROM recommendation_states");
        recommendationListRepository.deleteAllInBatch();
        assertThat(apply(originalRequest)).isEmpty();
        CookingApplicationRequest changedRequest = new CookingApplicationRequest(originalRequest.recommendationId(), RECIPE_ID, "\"" + "b".repeat(64) + "\"", originalRequest.idempotencyKey());
        assertFailure(changedRequest, RecommendationExceptionCode.IDEMPOTENCY_CONFLICT);
        assertThat(stockAmount()).isEqualByComparingTo("25");
        assertOutcome(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordsCookingWhenStockIsMissingOrUsageCannotBeCalculated(boolean unresolved) {
        if (unresolved) { jdbcTemplate.update("UPDATE ingredients SET measure_type='COUNT', quantity=2, weight_value=NULL, weight_unit='NONE' WHERE ingredient_id=?", stockId); }
        else { jdbcTemplate.update("DELETE FROM ingredients WHERE ingredient_id = ?", stockId); }
        assertThat(apply(request())).isPresent();
        assertOutcome(1);
        if (unresolved) { assertThat(stockAmount()).isEqualByComparingTo("2"); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRequestsConsumeOnceAndReturnReplayOrChangedDetail(boolean sameKey) throws Exception {
        CookingApplicationRequest firstRequest = request();
        CookingApplicationRequest secondRequest = firstRequest;
        if (!sameKey) { secondRequest = request(); }
        CookingApplicationRequest concurrentRequest = secondRequest;
        List<Object> results = concurrently(List.of(() -> apply(firstRequest), () -> apply(concurrentRequest)));
        assertThat(results.stream().filter(result -> result instanceof Optional<?> outcome && outcome.isPresent()).count()).isEqualTo(1);
        if (sameKey) { assertThat(results.stream().filter(result -> result instanceof Optional<?> outcome && outcome.isEmpty()).count()).isEqualTo(1); }
        else { assertThat(results).filteredOn(CustomException.class::isInstance).singleElement().satisfies(result ->
                assertThat(((CustomException) result).getExceptionCode()).isEqualTo(RecommendationExceptionCode.DETAIL_CHANGED)); }
        assertThat(stockAmount()).isEqualByComparingTo("100");
        assertOutcome(1);
    }

    @Test
    void crossUserKeyCollisionRollsBackTheLosingTransactionEvenWithAnOlderSnapshot() throws Exception {
        Fixture otherParticipant = ownerFixture("다른조리회원");
        jdbcTemplate.update("""
                INSERT INTO ingredients (refrigerator_id, owner_id, name, category, measure_type, quantity, weight_value, weight_unit, expiration_date, registration_source)
                VALUES (?, ?, '두부', 'TOFU_BEAN', 'WEIGHT', NULL, 400, 'G', ?, 'DIRECT')
                """, otherParticipant.refrigeratorId(), otherParticipant.userId(), BusinessTime.today(clock).plusDays(2));
        Long otherStockId = jdbcTemplate.queryForObject("SELECT ingredient_id FROM ingredients WHERE refrigerator_id = ?", Long.class, otherParticipant.refrigeratorId());
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(selectedList.getResultJson());
        snapshot.put("userId", otherParticipant.userId()).put("refrigeratorId", otherParticipant.refrigeratorId());
        ((ObjectNode) snapshot.path("recommendations").get(0).path("inventoryUsage").get(0)).put("ingredientId", otherStockId);
        RecommendationList otherList = recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(otherParticipant.userId()),
                refrigeratorRepository.getReferenceById(otherParticipant.refrigeratorId()), UUID.randomUUID().toString(), snapshot.toString()));
        RecommendationDetailResult otherDetail = new TransactionTemplate(transactionManager).execute(status ->
                recommendationDetailService.getForCooking(otherParticipant.userId(), otherParticipant.refrigeratorId(), otherList.getId(), RECIPE_ID));
        CookingApplicationRequest firstRequest = request();
        CookingApplicationRequest otherRequest = new CookingApplicationRequest(otherList.getId(), RECIPE_ID, recommendationDetailEtag.of(otherDetail), firstRequest.idempotencyKey());
        AtomicInteger readyCount = new AtomicInteger();
        CompletableFuture<Void> bothRead = new CompletableFuture<>();
        List<Object> results = concurrently(List.of(() -> applyWithEarlierSnapshot(refrigeratorParticipant, firstRequest, readyCount, bothRead),
                () -> applyWithEarlierSnapshot(otherParticipant, otherRequest, readyCount, bothRead)));
        assertThat(results).filteredOn(CustomException.class::isInstance).singleElement().satisfies(result ->
                assertThat(((CustomException) result).getExceptionCode()).isEqualTo(RecommendationExceptionCode.IDEMPOTENCY_CONFLICT));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cooking_records", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(cooking_count) FROM users", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(weight_value) FROM ingredients", BigDecimal.class)).isEqualByComparingTo("500");
    }

    @Test
    void countUpdateFailureRollsBackConsumedStockAndInsertedRecord() {
        CookingApplicationRequest confirmed = request();
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_cooking_count BEFORE UPDATE ON users FOR EACH ROW
                BEGIN IF NEW.cooking_count > OLD.cooking_count THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cook_count_failure'; END IF; END
                """);
        try {
            assertThatThrownBy(() -> apply(confirmed)).hasStackTraceContaining("cook_count_failure");
            assertThat(stockAmount()).isEqualByComparingTo("400");
            assertOutcome(0);
        } finally { jdbcTemplate.execute("DROP TRIGGER reject_cooking_count"); }
    }

    private CookingApplicationRequest request() {
        RecommendationDetailResult displayedDetail = recommendationDetailService.get(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), RECIPE_ID);
        return new CookingApplicationRequest(selectedList.getId(), RECIPE_ID, recommendationDetailEtag.of(displayedDetail), UUID.randomUUID().toString());
    }

    private Optional<RecommendationDetailResult> applyWithEarlierSnapshot(Fixture actor, CookingApplicationRequest request,
            AtomicInteger readyCount, CompletableFuture<Void> bothRead) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cooking_records WHERE idempotency_key = ?", Integer.class, request.idempotencyKey())).isZero();
            if (readyCount.incrementAndGet() == 2) { bothRead.complete(null); }
            bothRead.orTimeout(5, TimeUnit.SECONDS).join();
            return cookingApplicationService.apply(actor.userId(), actor.refrigeratorId(), request);
        });
    }

    private Optional<RecommendationDetailResult> apply(CookingApplicationRequest request) {
        return cookingApplicationService.apply(refrigeratorParticipant.userId(), refrigeratorParticipant.refrigeratorId(), request);
    }

    private void assertFailure(CookingApplicationRequest request, RecommendationExceptionCode code) {
        assertThatThrownBy(() -> apply(request)).isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getExceptionCode()).isEqualTo(code));
    }

    private BigDecimal stockAmount() { return jdbcTemplate.queryForObject("SELECT COALESCE(weight_value, quantity) FROM ingredients WHERE ingredient_id = ?", BigDecimal.class, stockId); }

    private void assertOutcome(int count) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cooking_records", Integer.class)).isEqualTo(count);
        assertThat(userRepository.findById(refrigeratorParticipant.userId()).orElseThrow().getCookingCount()).isEqualTo(count);
    }

    private void useCountStock() throws Exception {
        jdbcTemplate.update("UPDATE ingredients SET measure_type='COUNT', quantity=3, weight_value=NULL, weight_unit='NONE' WHERE ingredient_id=?", stockId);
        ObjectNode snapshot = (ObjectNode) jsonMapper.readTree(selectedList.getResultJson());
        ((ObjectNode) snapshot.path("recommendations").get(0).path("ingredientRequirements").get(0))
                .put("requiredAmount", 2).put("unit", "EA").put("amountText", "2개");
        jdbcTemplate.update("UPDATE recommendation_lists SET result_json = ? WHERE recommendation_list_id = ?", snapshot.toString(), selectedList.getId());
    }
}
