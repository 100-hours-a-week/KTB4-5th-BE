package com.dameokja.backend.recipe.domain;

import com.dameokja.backend.recipe.domain.RecommendationState.GenerationStatus;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationTrigger;

public record RecommendationGenerationFinished(Long userId, Long refrigeratorId, GenerationTrigger generationTrigger, GenerationStatus generationStatus) {
    public RecommendationGenerationFinished {
        if (userId == null || userId <= 0 || refrigeratorId == null || refrigeratorId <= 0 || generationTrigger == null
                || (generationStatus != GenerationStatus.COMPLETED && generationStatus != GenerationStatus.FAILED)) {
            throw new IllegalArgumentException("추천 요청자·냉장고와 종료 상태가 필요합니다.");
        }
    }
}
