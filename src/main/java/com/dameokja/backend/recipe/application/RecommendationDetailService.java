package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.InventoryUsage;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RecommendationDetailService {
    private final RecommendationStateRepository recommendationStateRepository;
    private final IngredientListService ingredientListService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public RecommendationDetailResult get(Long userId, Long refrigeratorId, String recipeId) {
        if (!recipeId.matches("[A-Z][A-Z0-9_]*:.+")) { throw new CustomException(GlobalExceptionCode.BAD_REQUEST); }
        RecommendationList selectedList = recommendationStateRepository.findByUserIdAndRefrigeratorId(userId, refrigeratorId)
                .map(RecommendationState::getCurrentList).orElse(null);
        Recipe recipe = selectedRecipe(selectedList, recipeId);
        Map<Long, AiRecipeInventoryItem> inventoryById = ingredientListService.getByIds(userId, refrigeratorId, mappedStockIds(recipe)).stream()
                .map(AiRecipeInventoryItem::from).collect(Collectors.toMap(AiRecipeInventoryItem::ingredientId, Function.identity()));
        if (recipe == null) { throw new CustomException(RecommendationExceptionCode.RECIPE_NOT_FOUND); }
        return RecommendationDetailMapper.from(recipe, inventoryById, BusinessTime.today(clock));
    }

    private Recipe selectedRecipe(RecommendationList selectedList, String recipeId) {
        if (selectedList == null) { return null; }
        AiRecipeRecommendationResponse snapshot = jsonMapper.readValue(selectedList.getResultJson(), AiRecipeRecommendationResponse.class);
        return snapshot.recommendations().stream().filter(recipe -> recipeId.equals(recipe.source() + ":" + recipe.sourceRecipeId())).findFirst().orElse(null);
    }

    private List<Long> mappedStockIds(Recipe recipe) {
        if (recipe == null || recipe.ingredients() == null || recipe.inventoryUsage() == null) { return List.of(); }
        Set<Integer> ingredientNos = recipe.ingredients().stream().map(ingredient -> ingredient.ingredientNo()).collect(Collectors.toSet());
        return recipe.inventoryUsage().stream().filter(usage -> !"UNUSED".equals(usage.allocationStatus()) && usage.allocations() != null)
                .filter(usage -> usage.allocations().stream().anyMatch(allocation -> ingredientNos.contains(allocation.ingredientNo())))
                .map(InventoryUsage::ingredientId).distinct().toList();
    }
}
