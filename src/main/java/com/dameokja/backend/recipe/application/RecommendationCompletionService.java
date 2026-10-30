package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationList;
import com.dameokja.backend.recipe.domain.RecommendationState;
import com.dameokja.backend.recipe.infrastructure.RecommendationListRepository;
import com.dameokja.backend.recipe.infrastructure.RecommendationStateRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.application.access.UserAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RecommendationCompletionService {
    private final RecommendationStateRepository recommendationStateRepository;
    private final RecommendationListRepository recommendationListRepository;
    private final JsonMapper jsonMapper;
    private final UserAccessService userAccessService;
    private final RefrigeratorAccessService refrigeratorAccessService;

    @Transactional
    public void apply(AiRecipeJobResponse recommendationJobResponse) {
        if (recommendationJobResponse == null || recommendationJobResponse.userId() == null) { return; }
        // 접수와 같은 잠금 순서를 지키며, 현재 사용 중인 냉장고 변경과 관계없이 요청 당시의 완료 이력을 저장한다.
        userAccessService.lockExisting(recommendationJobResponse.userId());
        refrigeratorAccessService.lockExisting(recommendationJobResponse.refrigeratorId());
        RecommendationState recommendationState = recommendationStateRepository
                .findByParticipantForUpdate(recommendationJobResponse.userId(), recommendationJobResponse.refrigeratorId()).orElse(null);
        if (recommendationState == null || !recommendationState.isGeneratingRequest(recommendationJobResponse.requestId())) { return; }
        updateGenerationStatus(recommendationState, recommendationJobResponse);
    }

    private void updateGenerationStatus(RecommendationState recommendationState, AiRecipeJobResponse recommendationJobResponse) {
        switch (recommendationJobResponse.status()) {
            case QUEUED -> { }
            case PROCESSING -> recommendationState.markProcessing();
            case FAILED -> recommendationState.failGeneration();
            case COMPLETED -> saveCompletedResult(recommendationState, recommendationJobResponse);
        }
    }

    private void saveCompletedResult(RecommendationState recommendationState, AiRecipeJobResponse recommendationJobResponse) {
        String resultJson = jsonMapper.writeValueAsString(recommendationJobResponse.result());
        RecommendationList completedList = new RecommendationList(recommendationState.getUser(), recommendationState.getRefrigerator(),
                recommendationJobResponse.requestId(), resultJson);
        RecommendationList savedList = recommendationListRepository.save(completedList);
        recommendationState.completeGeneration(savedList);
    }
}
