package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest.Mode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationSubmissionService {
    private final RecommendationInventoryService recommendationInventoryService;
    private final RecommendationOutboxService recommendationOutboxService;

    // 재고 조회와 요청 준비가 끝난 뒤 저장 트랜잭션을 시작한다.
    public RecommendationSubmissionResult submit(Long userId, Long refrigeratorId, Mode mode) {
        AiRecipeRecommendationRequest recommendationRequest = recommendationInventoryService.prepare(userId, refrigeratorId, mode);
        return recommendationOutboxService.enqueue(recommendationRequest);
    }
}
