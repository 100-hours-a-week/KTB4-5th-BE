package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.application.access.UserAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecommendationAcceptanceService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final UserAccessService userAccessService;
    private final RecommendationStateRepository recommendationStateRepository;
    private final RecommendationOutboxService recommendationOutboxService;
    private final RecommendationDailyQuotaService recommendationDailyQuotaService;

    @Transactional
    public RecommendationSubmissionResult accept(Long userId, AiRecipeRecommendationRequest recommendationRequest) {
        // 상태 행이 없는 최초 접수도 사용자 잠금 아래 직렬화한다.
        Refrigerator refrigerator = refrigeratorAccessService.lockForWrite(userId, recommendationRequest.refrigeratorId());
        RecommendationState recommendationState = recommendationStateRepository.findByParticipantForUpdate(userId, refrigerator.getId())
                .orElseGet(() -> recommendationStateRepository.save(new RecommendationState(userAccessService.getActive(userId), refrigerator)));
        recommendationState.validateRequestAllowed();
        if (canReuse(recommendationState, recommendationRequest)) {
            return new RecommendationSubmissionResult(null, null, recommendationState.getCurrentList().getRequestId(), refrigerator.getId(), null,
                    recommendationDailyQuotaService.current(userId), recommendationState.getCurrentList().getId());
        }
        recommendationState.queueUserRequest(recommendationRequest.requestId());
        RecommendationDailyGeneration dailyGeneration = recommendationDailyQuotaService.reserve(userId, recommendationRequest.requestId());
        return recommendationOutboxService.enqueue(recommendationRequest).withDailyGeneration(dailyGeneration);
    }

    private boolean canReuse(RecommendationState recommendationState, AiRecipeRecommendationRequest recommendationRequest) {
        return recommendationState.getCurrentList() != null && recommendationOutboxService.matching(recommendationState.getCurrentList().getRequestId()).stream()
                .map(RecommendationOutboxService.Event::request)
                .anyMatch(previousRecommendationRequest -> sameInputs(previousRecommendationRequest, recommendationRequest));
    }

    private boolean sameInputs(AiRecipeRecommendationRequest previousRecommendationRequest, AiRecipeRecommendationRequest recommendationRequest) {
        return previousRecommendationRequest.userId().equals(recommendationRequest.userId())
                && previousRecommendationRequest.refrigeratorId().equals(recommendationRequest.refrigeratorId())
                && previousRecommendationRequest.inventoryFingerprint().equals(recommendationRequest.inventoryFingerprint())
                && RecommendationOptions.from(previousRecommendationRequest).effective().equals(RecommendationOptions.from(recommendationRequest).effective());
    }
}
