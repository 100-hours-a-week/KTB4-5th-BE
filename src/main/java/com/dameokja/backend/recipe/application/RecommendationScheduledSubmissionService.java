package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.recipe.application.RecommendationDailyJobQueue.Lease;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.user.domain.UserExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class RecommendationScheduledSubmissionService {
    private final RecommendationInventoryService inventory;
    private final RecommendationPreferenceService preferences;
    private final RecommendationScheduledAcceptanceService acceptance;
    private final RecommendationDailyJobQueue queue;

    public void submit(Lease lease) {
        try {
            AiRecipeRecommendationRequest request = inventory.prepare(lease.userId(), lease.refrigeratorId(), preferences.last(lease.userId()), lease.requestId());
            acceptance.accept(lease, request);
        } catch (CustomException exception) {
            if (!(exception.getExceptionCode() instanceof RefrigeratorExceptionCode) && !(exception.getExceptionCode() instanceof UserExceptionCode)) { throw exception; }
            queue.skip(lease, exception.getExceptionCode().getCode());
        }
    }
}
