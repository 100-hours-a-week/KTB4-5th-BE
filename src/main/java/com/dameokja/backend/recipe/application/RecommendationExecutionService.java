package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.RecommendationJobStore;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeClient;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class RecommendationExecutionService {
    private final RecommendationOutboxService outbox;
    private final AiRecipeClient ai;
    private final RecommendationJobStore jobs;

    public RecommendationJobStore.Job submit(String eventId) {
        AiRecipeRecommendationRequest request = outbox.request(eventId);
        RecommendationJobStore.Job job = jobs.find(eventId).orElseGet(() -> jobs.accept(eventId, ai.submit(request)));
        // 접수 정보를 Redis에 보관해야 이후 조회에서 같은 AI 작업을 이어갈 수 있다.
        outbox.markSent(eventId);
        return job;
    }

    public RecommendationJobStore.Job refresh(String eventId) {
        AiRecipeRecommendationRequest request = outbox.request(eventId);
        RecommendationJobStore.Job job = jobs.find(eventId).orElseThrow();
        if (job.terminal()) {
            return job;
        }
        return jobs.update(eventId, ai.get(job.acceptance().jobId(), request));
    }
}
