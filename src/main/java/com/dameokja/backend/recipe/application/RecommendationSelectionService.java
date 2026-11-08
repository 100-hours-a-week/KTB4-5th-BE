package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationSelectionService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final RecommendationStateRepository recommendationStateRepository;

    @Transactional
    public void selectLatest(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.lockForWrite(userId, refrigeratorId);
        RecommendationState recommendationState = recommendationStateRepository.findByParticipantForUpdate(userId, refrigeratorId).orElse(null);
        if (recommendationState != null) { recommendationState.selectLatestList(); }
    }
}
