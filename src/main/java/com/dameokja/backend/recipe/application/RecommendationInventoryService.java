package com.dameokja.backend.recipe.application;

import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationInventoryService {
    private final IngredientListService ingredientListService;

    // 전체 재고 조회의 트랜잭션이 끝난 뒤 지문과 AI 요청을 만든다.
    public AiRecipeRecommendationRequest prepare(Long userId, Long refrigeratorId, RecommendationOptions recommendationOptions) {
        RecommendationOptions resolvedOptions = recommendationOptions == null ? RecommendationOptions.empty() : recommendationOptions;
        List<AiRecipeInventoryItem> inventory = ingredientListService.getAll(userId, refrigeratorId).stream()
                .map(AiRecipeInventoryItem::from)
                .toList();
        return resolvedOptions.toRequest(UUID.randomUUID().toString(), refrigeratorId, inventory);
    }
}
