package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationSubmissionService {
    private final RecommendationInventoryService recommendationInventoryService;
    private final RecommendationAcceptanceService recommendationAcceptanceService;

    // 재고 조회와 요청 준비가 끝난 뒤 저장 트랜잭션을 시작한다.
    public RecommendationSubmissionResult submit(Long userId, Long refrigeratorId, RecommendationOptions recommendationOptions) {
        AiRecipeRecommendationRequest recommendationRequest = recommendationInventoryService.prepare(userId, refrigeratorId, recommendationOptions);
        return recommendationAcceptanceService.accept(userId, recommendationRequest);
    }
}
