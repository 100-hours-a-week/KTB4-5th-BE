package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.domain.RegistrationSource;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class RecommendationSubmissionServiceTest extends ServiceIntegrationTest {
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private RecommendationInventoryService recommendationInventoryService;
    @Autowired private RecommendationAcceptanceService recommendationAcceptanceService;
    @Autowired private RecommendationDailyQuotaService recommendationDailyQuotaService;
    @Autowired private RecommendationOutboxRepository recommendationOutboxRepository;
    @Autowired private RecommendationStateRepository recommendationStateRepository;
    @Autowired private RecommendationListRepository recommendationListRepository;
    @MockitoBean private AiRecipeClient aiRecipeClient;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JsonMapper jsonMapper;

    @Test
    void persistsTheFrozenRequestTogetherWithQueuedParticipantState() {
        Fixture refrigeratorOwner = ownerFixture("추천회원");
        Long ingredientId = persistIngredient(refrigeratorOwner);

        RecommendationSubmissionResult firstSubmissionResult = recommendationSubmissionService.submit(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId(), null);
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> entityManager.find(Ingredient.class, ingredientId)
                .updateMeasurement(Measurement.of(MeasureType.COUNT, 3, null, WeightUnit.NONE)));

        assertThat(firstSubmissionResult.refrigeratorId()).isEqualTo(refrigeratorOwner.refrigeratorId());
        assertThat(firstSubmissionResult.acceptedAt()).isNotNull();
        assertThat(recommendationOutboxRepository.count()).isEqualTo(1);
        AiRecipeRecommendationRequest storedRequest = jsonMapper.readValue(
                recommendationOutboxRepository.findByEventId(firstSubmissionResult.eventId()).orElseThrow().getPayload(), AiRecipeRecommendationRequest.class);
        assertThat(storedRequest.requestId()).isEqualTo(firstSubmissionResult.requestId());
        assertThat(storedRequest.inventory()).singleElement().satisfies(inventoryItem -> assertThat(inventoryItem.quantity()).isEqualTo(2));
        RecommendationState state = state(refrigeratorOwner);
        assertThat(state.getActiveRequestId()).isEqualTo(firstSubmissionResult.requestId());
        assertThat(state.getGenerationStatus()).isEqualTo(RecommendationState.GenerationStatus.QUEUED);
        assertThat(state.getGenerationTrigger()).isEqualTo(RecommendationState.GenerationTrigger.USER);
        assertThat(state.getCurrentList()).isNull();
        assertThat(state.getLatestList()).isNull();
        verifyNoInteractions(aiRecipeClient);
    }

    @Test
    void freezesEveryUserOptionInTheOutboxPayload() {
        Fixture refrigeratorOwner = ownerFixture("옵션회원");
        RecommendationOptions options = new RecommendationOptions(Mode.INCLUDE_EXPIRED, List.of("한식"), 3, 4,
                List.of(AiRecipeRecommendationRequest.Strategy.SIMPLE_COOKING), List.of("우유"), List.of("소금"), List.of("MANUAL:123"));
        RecommendationSubmissionResult submission = recommendationSubmissionService.submit(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId(), options);
        AiRecipeRecommendationRequest stored = jsonMapper.readValue(
                recommendationOutboxRepository.findByEventId(submission.eventId()).orElseThrow().getPayload(), AiRecipeRecommendationRequest.class);
        assertThat(stored).isEqualTo(options.toRequest(submission.requestId(), refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId(), List.of()));
    }

    @Test
    void doesNotAcceptTheRequestWhenWritingItsOutboxEventFails() {
        Fixture refrigeratorOwner = ownerFixture("롤백회원");
        jdbcTemplate.execute("CREATE TRIGGER recommendation_outbox_failure BEFORE INSERT ON recommendation_outbox "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'recommendation_outbox_test_failure'");
        try {
            assertThatThrownBy(() -> submit(refrigeratorOwner))
                    .hasStackTraceContaining("recommendation_outbox_test_failure");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER recommendation_outbox_failure");
        }

        assertThat(recommendationOutboxRepository.count()).isZero();
        assertThat(recommendationStateRepository.count()).isZero();
        assertThat(recommendationDailyQuotaService.current(refrigeratorOwner.userId()).remaining()).isEqualTo(10);
    }

    @Test
    void rejectsAnUnauthorizedSubmissionBeforeCreatingAnOutboxEvent() {
        Fixture refrigeratorOwner = ownerFixture("권한회원");
        Long unauthorizedUserId = user("외부회원").getId();

        assertThatThrownBy(() -> recommendationSubmissionService.submit(unauthorizedUserId, refrigeratorOwner.refrigeratorId(), null))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(RefrigeratorExceptionCode.ACCESS_DENIED));
        assertThat(recommendationOutboxRepository.count()).isZero();
        assertThat(recommendationStateRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "PROCESSING"})
    void blocksAdditionalRequestsWhileGenerationIsRunning(String generationStatus) {
        Fixture participant = ownerFixture("진행회원");
        RecommendationSubmissionResult accepted = submit(participant);
        jdbcTemplate.update("UPDATE recommendation_states SET generation_status = ? WHERE user_id = ?", generationStatus, participant.userId());
        assertRejected(participant, RecommendationExceptionCode.GENERATION_IN_PROGRESS);
        assertThat(state(participant).getActiveRequestId()).isEqualTo(accepted.requestId());
        assertThat(recommendationOutboxRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blocksCompletedResultsUntilTheyAreSelected(boolean hasCurrentResult) {
        Fixture participant = ownerFixture("완료회원");
        submit(participant);
        Long currentListId = hasCurrentResult ? saveResult(participant) : null;
        Long latestListId = saveResult(participant);
        finishRequest(participant, currentListId, latestListId);
        assertRejected(participant, RecommendationExceptionCode.UNSELECTED_RESULT);
        assertThat(recommendationOutboxRepository.count()).isEqualTo(1);
    }

    @Test
    void allowsAnotherRequestAfterSelectingTheLatestResultAndKeepsItsSelection() {
        Fixture participant = ownerFixture("선택회원");
        submit(participant);
        Long selectedListId = saveResult(participant);
        finishRequest(participant, selectedListId, selectedListId);
        RecommendationSubmissionResult next = submit(participant);
        RecommendationState state = state(participant);
        assertThat(state.getCurrentList().getId()).isEqualTo(selectedListId);
        assertThat(state.getLatestList().getId()).isEqualTo(selectedListId);
        assertThat(state.getActiveRequestId()).isEqualTo(next.requestId());
        assertThat(recommendationOutboxRepository.count()).isEqualTo(2);
    }

    @Test
    void allowsAnotherRequestAfterFailure() {
        Fixture participant = ownerFixture("실패회원");
        RecommendationSubmissionResult failed = submit(participant);
        jdbcTemplate.update("UPDATE recommendation_states SET generation_status = 'FAILED', active_request_id = NULL WHERE user_id = ?", participant.userId());
        RecommendationSubmissionResult next = submit(participant);
        assertThat(next.requestId()).isNotEqualTo(failed.requestId());
        assertThat(state(participant).getActiveRequestId()).isEqualTo(next.requestId());
        assertThat(recommendationOutboxRepository.count()).isEqualTo(2);
    }

    @Test
    void allowsDifferentParticipantsToGenerateForTheSameRefrigerator() {
        Fixture owner = ownerFixture("공유소유자");
        Long memberId = user("공유참여자").getId();
        join(userRepository.getReferenceById(memberId), refrigeratorRepository.getReferenceById(owner.refrigeratorId()), true, false);
        submit(owner);
        RecommendationSubmissionResult memberSubmission = recommendationSubmissionService.submit(memberId, owner.refrigeratorId(), null);
        assertThat(state(owner).getActiveRequestId()).isNotEqualTo(memberSubmission.requestId());
        assertThat(recommendationStateRepository.findByUserIdAndRefrigeratorId(memberId, owner.refrigeratorId()).orElseThrow().getActiveRequestId())
                .isEqualTo(memberSubmission.requestId());
        assertThat(recommendationStateRepository.count()).isEqualTo(2);
        assertThat(recommendationOutboxRepository.count()).isEqualTo(2);
    }

    @Test
    void rechecksAccessWhenMembershipChangesAfterPreparingTheRequest() {
        Fixture participant = ownerFixture("변경권한회원");
        AiRecipeRecommendationRequest prepared = recommendationInventoryService.prepare(participant.userId(), participant.refrigeratorId(), null);
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus ->
                refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(participant.userId()).orElseThrow().deactivate());
        assertThatThrownBy(() -> recommendationAcceptanceService.accept(participant.userId(), prepared))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(RefrigeratorExceptionCode.ACCESS_DENIED));
        assertThat(recommendationStateRepository.count()).isZero();
        assertThat(recommendationOutboxRepository.count()).isZero();
    }

    @Test
    void acceptsExactlyOneConcurrentFirstRequest() throws Exception {
        Fixture participant = ownerFixture("동시회원");
        List<Object> results = concurrently(List.of(() -> submit(participant), () -> submit(participant)));
        assertThat(results.stream().filter(RecommendationSubmissionResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(CustomException.class::isInstance).map(CustomException.class::cast))
                .singleElement().satisfies(exception -> assertThat(exception.getExceptionCode()).isEqualTo(RecommendationExceptionCode.GENERATION_IN_PROGRESS));
        assertThat(recommendationStateRepository.count()).isEqualTo(1);
        assertThat(recommendationOutboxRepository.count()).isEqualTo(1);
        RecommendationSubmissionResult accepted = results.stream().filter(RecommendationSubmissionResult.class::isInstance)
                .map(RecommendationSubmissionResult.class::cast).findFirst().orElseThrow();
        assertThat(state(participant).getActiveRequestId()).isEqualTo(accepted.requestId());
    }

    private RecommendationSubmissionResult submit(Fixture participant) {
        return recommendationSubmissionService.submit(participant.userId(), participant.refrigeratorId(), null);
    }

    private RecommendationState state(Fixture participant) {
        return recommendationStateRepository.findByUserIdAndRefrigeratorId(participant.userId(), participant.refrigeratorId()).orElseThrow();
    }

    private void assertRejected(Fixture participant, RecommendationExceptionCode expected) {
        RecommendationDailyGeneration before = recommendationDailyQuotaService.current(participant.userId());
        assertThatThrownBy(() -> submit(participant)).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(expected));
        assertThat(recommendationDailyQuotaService.current(participant.userId())).isEqualTo(before);
    }

    private Long saveResult(Fixture participant) {
        return recommendationListRepository.saveAndFlush(new RecommendationList(userRepository.getReferenceById(participant.userId()),
                refrigeratorRepository.getReferenceById(participant.refrigeratorId()), UUID.randomUUID().toString(), "{}")).getId();
    }

    private void finishRequest(Fixture participant, Long currentListId, Long latestListId) {
        jdbcTemplate.update("""
                UPDATE recommendation_states SET generation_status = 'COMPLETED', active_request_id = NULL,
                current_list_id = ?, latest_list_id = ? WHERE user_id = ? AND refrigerator_id = ?
                """, currentListId, latestListId, participant.userId(), participant.refrigeratorId());
    }

    private Long persistIngredient(Fixture refrigeratorOwner) {
        return new TransactionTemplate(transactionManager).execute(transactionStatus -> {
            Ingredient ingredient = new Ingredient(refrigeratorRepository.getReferenceById(refrigeratorOwner.refrigeratorId()),
                    new IngredientDetails("국산 두부", IngredientCategory.TOFU_BEAN, StorageType.FROZEN,
                            Measurement.of(MeasureType.COUNT, 2, null, WeightUnit.NONE), LocalDate.of(2026, 12, 1)),
                    RegistrationSource.DIRECT, userRepository.getReferenceById(refrigeratorOwner.userId()));
            entityManager.persist(ingredient);
            return ingredient.getId();
        });
    }

}
