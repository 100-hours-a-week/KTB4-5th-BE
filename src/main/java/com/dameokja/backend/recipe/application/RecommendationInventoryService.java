package com.dameokja.backend.recipe.application;

import com.dameokja.backend.ingredient.application.list.IngredientListService;
import com.dameokja.backend.recipe.infrastructure.ai.AiInventoryFingerprint;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeInventoryItem;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationInventoryService {
    private static final int RECOMMENDATION_LIMIT = 10;
    private final IngredientListService ingredientListService;

    // 전체 재고 조회의 트랜잭션이 끝난 뒤 지문과 AI 요청을 만든다.
    public AiRecipeRecommendationRequest prepare(Long userId, Long refrigeratorId, Mode mode) {
        Mode resolvedMode = mode == null ? Mode.INCLUDE_EXPIRED : mode;
        List<AiRecipeInventoryItem> inventory = ingredientListService.getAll(userId, refrigeratorId).stream()
                .map(AiRecipeInventoryItem::from)
                .toList();
        String fingerprint = AiInventoryFingerprint.of(inventory);
        String requestId = requestId(refrigeratorId, fingerprint, resolvedMode);
        return new AiRecipeRecommendationRequest(requestId, refrigeratorId, fingerprint, resolvedMode, inventory, List.of(), RECOMMENDATION_LIMIT);
    }

    private String requestId(Long refrigeratorId, String fingerprint, Mode mode) {
        String identity = String.join("\n", "recipe-recommendation", refrigeratorId.toString(), fingerprint, mode.name());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
