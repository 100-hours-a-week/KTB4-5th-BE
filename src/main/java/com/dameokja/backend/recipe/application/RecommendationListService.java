package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.recipe.application.RecommendationListResult.RecipeSummary;
import com.dameokja.backend.recipe.application.RecommendationListResult.Status;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationStatus;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.InventoryUsage;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RecommendationListService {
    private static final int MAX_RECIPES = 10;
    private final IngredientListService ingredientListService;
    private final RecommendationStateRepository recommendationStateRepository;
    private final RecommendationOutboxRepository recommendationOutboxRepository;
    private final RecommendationDailyQuotaService recommendationDailyQuotaService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public RecommendationListResult get(Long userId, Long refrigeratorId, int limit) {
        if (limit < 1 || limit > MAX_RECIPES) { throw new CustomException(GlobalExceptionCode.BAD_REQUEST); }
        RecommendationState recommendationState = recommendationStateRepository.findByUserIdAndRefrigeratorId(userId, refrigeratorId).orElse(null);
        RecommendationList selectedList = recommendationState == null ? null : recommendationState.getCurrentList();
        AiRecipeRecommendationResponse snapshot = selectedList == null ? null : jsonMapper.readValue(selectedList.getResultJson(), AiRecipeRecommendationResponse.class);
        List<RecipeSummary> recipeSummaries = recipes(snapshot, userId, refrigeratorId, limit);
        RecommendationDailyGeneration dailyGeneration = recommendationDailyQuotaService.current(userId);
        String activeJobId = activeJobId(recommendationState);
        return toResult(recommendationState, snapshot, recipeSummaries, dailyGeneration, activeJobId);
    }

    private RecommendationListResult toResult(RecommendationState recommendationState, AiRecipeRecommendationResponse snapshot,
            List<RecipeSummary> recipeSummaries, RecommendationDailyGeneration dailyGeneration, String activeJobId) {
        RecommendationList selectedList = recommendationState == null ? null : recommendationState.getCurrentList();
        boolean hasNewRecommendation = recommendationState != null && recommendationState.getLatestList() != null
                && (selectedList == null || !recommendationState.getLatestList().getId().equals(selectedList.getId()));
        Status recommendationStatus = status(recommendationState, selectedList, hasNewRecommendation);
        RecommendationListResult.RecommendationListResultBuilder resultBuilder = RecommendationListResult.builder()
                .status(recommendationStatus)
                .activeJobId(activeJobId)
                .hasNewRecommendation(hasNewRecommendation)
                .dailyGeneration(dailyGeneration)
                .recipes(recipeSummaries);
        if (selectedList != null) { resultBuilder.recommendationId(selectedList.getId().toString()); }
        if (snapshot != null) {
            OffsetDateTime generatedAt = snapshot.generatedAt().atZoneSameInstant(BusinessTime.ZONE).toOffsetDateTime();
            resultBuilder.generatedAt(generatedAt);
        }
        if (recommendationState != null) {
            resultBuilder.generationTrigger(recommendationState.getGenerationTrigger());
            resultBuilder.generationStatus(recommendationState.getGenerationStatus());
        }
        return resultBuilder.build();
    }

    private Status status(RecommendationState recommendationState, RecommendationList selectedList, boolean hasNewRecommendation) {
        if (recommendationState != null && (recommendationState.getGenerationStatus() == GenerationStatus.QUEUED
                || recommendationState.getGenerationStatus() == GenerationStatus.PROCESSING)) { return Status.GENERATING; }
        if (selectedList == null) { return Status.EMPTY; }
        return hasNewRecommendation ? Status.STALE : Status.READY;
    }

    private String activeJobId(RecommendationState recommendationState) {
        if (recommendationState == null || recommendationState.getActiveRequestId() == null) { return null; }
        return recommendationOutboxRepository.findFirstByRequestIdOrderByIdAsc(recommendationState.getActiveRequestId()).map(RecommendationOutbox::getEventId).orElse(null);
    }

    private List<RecipeSummary> recipes(AiRecipeRecommendationResponse snapshot, Long userId, Long refrigeratorId, int limit) {
        List<Recipe> selectedRecipes = snapshot == null ? List.of() : snapshot.recommendations().stream().limit(limit).toList();
        List<Long> ingredientIds = selectedRecipes.stream()
                .flatMap(recipe -> recipe.inventoryUsage() == null ? Stream.empty() : recipe.inventoryUsage().stream())
                .filter(usage -> usage.allocations() != null && !usage.allocations().isEmpty()).map(InventoryUsage::ingredientId).distinct().toList();
        List<AiRecipeInventoryItem> inventory = ingredientListService.getByIds(userId, refrigeratorId, ingredientIds).stream().map(AiRecipeInventoryItem::from).toList();
        Map<Long, AiRecipeInventoryItem> inventoryById = inventory.stream().collect(Collectors.toMap(AiRecipeInventoryItem::ingredientId, Function.identity()));
        LocalDate today = BusinessTime.today(clock);
        return selectedRecipes.stream().map(recipe -> RecommendationRecipeSummary.from(recipe, inventoryById, today)).toList();
    }
}
