package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.List;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class RecommendationSubmissionServiceTest extends ServiceIntegrationTest {
    @Autowired private RecommendationOutboxService recommendationOutboxService;
    @Autowired private RecommendationSubmissionService recommendationSubmissionService;
    @Autowired private RecommendationOutboxRepository recommendationOutboxRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JsonMapper jsonMapper;

    @AfterEach
    void removeOutboxEvents() {
        recommendationOutboxRepository.deleteAll();
    }

    @Test
    void persistsTheFrozenRequestAndAcceptsRepeatedRequestsAsSeparateEvents() {
        Fixture refrigeratorOwner = ownerFixture("추천회원");
        Long ingredientId = persistIngredient(refrigeratorOwner);

        RecommendationSubmissionResult firstSubmissionResult = recommendationSubmissionService.submit(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId(), null);
        RecommendationSubmissionResult secondSubmissionResult = recommendationSubmissionService.submit(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId(), null);
        new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> entityManager.find(Ingredient.class, ingredientId)
                .updateMeasurement(Measurement.of(MeasureType.COUNT, 3, null, WeightUnit.NONE)));

        assertThat(firstSubmissionResult.eventId()).isNotEqualTo(secondSubmissionResult.eventId());
        assertThat(firstSubmissionResult.requestId()).isNotEqualTo(secondSubmissionResult.requestId());
        assertThat(firstSubmissionResult.refrigeratorId()).isEqualTo(refrigeratorOwner.refrigeratorId());
        assertThat(firstSubmissionResult.acceptedAt()).isNotNull();
        assertThat(recommendationOutboxRepository.count()).isEqualTo(2);
        AiRecipeRecommendationRequest storedRequest = jsonMapper.readValue(
                recommendationOutboxRepository.findByEventId(firstSubmissionResult.eventId()).orElseThrow().getPayload(), AiRecipeRecommendationRequest.class);
        assertThat(storedRequest.requestId()).isEqualTo(firstSubmissionResult.requestId());
        assertThat(storedRequest.inventory()).singleElement().satisfies(inventoryItem -> assertThat(inventoryItem.quantity()).isEqualTo(2));
    }

    @Test
    void freezesEveryUserOptionInTheOutboxPayload() {
        Fixture refrigeratorOwner = ownerFixture("옵션회원");
        RecommendationOptions options = new RecommendationOptions(Mode.INCLUDE_EXPIRED, List.of("한식"), 3, 4,
                List.of(AiRecipeRecommendationRequest.Strategy.SIMPLE_COOKING), List.of("우유"), List.of("소금"), List.of("MANUAL:123"));
        RecommendationSubmissionResult submission = recommendationSubmissionService.submit(refrigeratorOwner.userId(), refrigeratorOwner.refrigeratorId(), options);
        AiRecipeRecommendationRequest stored = jsonMapper.readValue(
                recommendationOutboxRepository.findByEventId(submission.eventId()).orElseThrow().getPayload(), AiRecipeRecommendationRequest.class);
        assertThat(stored).isEqualTo(options.toRequest(submission.requestId(), refrigeratorOwner.refrigeratorId(), List.of()));
    }

    @Test
    void doesNotAcceptTheRequestWhenWritingItsOutboxEventFails() {
        Fixture refrigeratorOwner = ownerFixture("롤백회원");
        jdbcTemplate.execute("CREATE TRIGGER recommendation_outbox_failure BEFORE INSERT ON recommendation_outbox "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'recommendation_outbox_test_failure'");
        try {
            assertThatThrownBy(() -> recommendationOutboxService.enqueue(createRecommendationRequest(refrigeratorOwner.refrigeratorId())))
                    .hasStackTraceContaining("recommendation_outbox_test_failure");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER recommendation_outbox_failure");
        }

        assertThat(recommendationOutboxRepository.count()).isZero();
    }

    @Test
    void rejectsAnUnauthorizedSubmissionBeforeCreatingAnOutboxEvent() {
        Fixture refrigeratorOwner = ownerFixture("권한회원");
        Long unauthorizedUserId = user("외부회원").getId();

        assertThatThrownBy(() -> recommendationSubmissionService.submit(unauthorizedUserId, refrigeratorOwner.refrigeratorId(), null))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(RefrigeratorExceptionCode.ACCESS_DENIED));
        assertThat(recommendationOutboxRepository.count()).isZero();
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

    private AiRecipeRecommendationRequest createRecommendationRequest(Long refrigeratorId) {
        return AiRecipeRecommendationRequest.create("fixed-request-id", refrigeratorId, Mode.INCLUDE_EXPIRED, List.of(), List.of(), 10);
    }
}
