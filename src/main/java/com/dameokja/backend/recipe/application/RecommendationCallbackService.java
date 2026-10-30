package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.RecommendationJobStore;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import com.dameokja.backend.recipe.domain.RecommendationOutbox.Status;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationCallbackService {
    private final RecommendationOutboxService recommendationOutboxService;
    private final RecommendationJobStore recommendationJobStore;
    private final RecommendationCompletionService recommendationCompletionService;

    public void receive(AiRecipeJobResponse aiRecipeJobResponse) {
        if (aiRecipeJobResponse.status() != AiRecipeJobResponse.Status.COMPLETED && aiRecipeJobResponse.status() != AiRecipeJobResponse.Status.FAILED) {
            throw new IllegalArgumentException("종료된 추천 작업만 수신할 수 있습니다.");
        }
        List<RecommendationOutboxService.Event> outboxEvents = recommendationOutboxService.matching(aiRecipeJobResponse.requestId());
        if (outboxEvents.isEmpty()) { throw new NoSuchElementException(); }
        List<String> activeEventIds = new ArrayList<>();
        for (RecommendationOutboxService.Event outboxEvent : outboxEvents) {
            aiRecipeJobResponse.validateFor(aiRecipeJobResponse.jobId(), outboxEvent.request());
            Optional<RecommendationJobStore.Job> storedJob = recommendationJobStore.find(outboxEvent.eventId());
            if (storedJob.isEmpty() && outboxEvent.status() == Status.PENDING) { throw new IllegalStateException(); }
            if (storedJob.isEmpty()) { continue; }
            if (!storedJob.orElseThrow().acceptance().jobId().equals(aiRecipeJobResponse.jobId())) { throw new IllegalArgumentException("AI 작업 ID가 일치하지 않습니다."); }
            activeEventIds.add(outboxEvent.eventId());
        }
        for (String eventId : activeEventIds) {
            RecommendationJobStore.Job updatedJob = recommendationJobStore.update(eventId, aiRecipeJobResponse);
            recommendationCompletionService.apply(updatedJob.view());
        }
    }
}
