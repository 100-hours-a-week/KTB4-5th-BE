package com.dameokja.backend.recipe.infrastructure.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.client.RestClientException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRecipeRecommendationResponse(
        String recommendationId, Long refrigeratorId, String inventoryFingerprint, String recommendationMode,
        List<Recipe> recommendations, List<Recipe> confirmationCandidates, List<String> uncoveredExpiringIngredients,
        OffsetDateTime generatedAt, String recommendationEngine, MealPlan mealPlan, List<String> warnings, Trace trace,
        List<String> preferenceTags, Map<String, Object> context) {
    public AiRecipeRecommendationResponse {
        if (recommendationId == null || recommendationId.isBlank() || recommendations == null || generatedAt == null) {
            throw new RestClientException("AI 추천 응답 형식이 올바르지 않습니다.");
        }
    }

    void validateFor(AiRecipeRecommendationRequest request) {
        if (!Objects.equals(refrigeratorId, request.refrigeratorId()) || !request.inventoryFingerprint().equals(inventoryFingerprint)
                || !request.recommendationMode().name().equals(recommendationMode)) {
            throw new RestClientException("AI 추천 응답이 요청 냉장고·재고 지문·모드와 일치하지 않습니다.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recipe(
            String source, String sourceRecipeId, String sourceRevision, String name, String imageUrl, String sourceUrl,
            BigDecimal baseServings, List<Ingredient> ingredients, List<Step> steps, BigDecimal score, ScoreBreakdown scoreBreakdown,
            List<String> usesInventory, List<String> usesExpiringIngredients, List<String> missingIngredients,
            String excludedIngredientCheck, String reason, String strategy, Integer targetServings, String quantityStatus, String quantityScope,
            List<IngredientRequirement> ingredientRequirements, List<InventoryUsage> inventoryUsage,
            List<Integer> essentialIngredientNos, List<Integer> missingEssentialIngredientNos, String feasibility,
            List<String> confirmationReasons, List<String> usesExpiringMainIngredients, String preparationNote,
            String preferenceMatch, String preferenceEvidence, List<PreferenceAssessment> preferenceAssessments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ingredient(Integer ingredientNo, String name, String amountText, String measureType, BigDecimal amountValue, String unit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(Integer stepNo, String instruction, String imageUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoreBreakdown(BigDecimal expiringIngredient, BigDecimal inventoryCoverage, BigDecimal missingIngredientPenalty, BigDecimal variety) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IngredientRequirement(Integer ingredientNo, String name, String amountText, BigDecimal requiredAmount, String unit,
            BigDecimal allocatedAmount, BigDecimal shortageAmount, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InventoryUsage(Long ingredientId, String name, String unit, BigDecimal availableAmount, BigDecimal usedAmount, BigDecimal remainingAmount,
            String allocationStatus, List<Allocation> allocations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Allocation(Integer ingredientNo, BigDecimal usedAmount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreferenceAssessment(String tag, String status, String evidence) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MealPlan(List<Long> focusIngredientIds, List<String> ideas) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trace(String policyVersion, String model, String promptVersion, Integer aiCalls, Integer searchCount,
            Integer candidateCount, Integer externalSearchCount, Integer webSearchCount, Integer preferenceDecisionCacheHits,
            Integer webDiscoveredCount, Integer webExtractionCalls, Integer webSearchCacheHits, Integer webExtractionCacheHits,
            Map<String, Integer> webParseFailureCounts, Integer aiCandidateCount, Integer aiInputBytes, Map<String, BigDecimal> timingsMs) {}
}
