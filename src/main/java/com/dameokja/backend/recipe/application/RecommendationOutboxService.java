package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RecommendationOutboxService {
    private final RecommendationOutboxRepository recommendationOutboxRepository;
    private final JsonMapper jsonMapper;
    private final RefrigeratorAccessService refrigeratorAccessService;

    @Transactional
    public RecommendationSubmissionResult enqueue(AiRecipeRecommendationRequest recommendationRequest) {
        Refrigerator refrigerator = refrigeratorAccessService.getReferenceById(recommendationRequest.refrigeratorId());
        RecommendationOutbox recommendationOutbox = recommendationOutboxRepository.saveAndFlush(new RecommendationOutbox(UUID.randomUUID().toString(),
                refrigerator, recommendationRequest.requestId(), jsonMapper.writeValueAsString(recommendationRequest)));
        return new RecommendationSubmissionResult(recommendationOutbox.getId(), recommendationOutbox.getEventId(), recommendationOutbox.getRequestId(),
                recommendationRequest.refrigeratorId(), recommendationOutbox.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public AiRecipeRecommendationRequest request(String eventId) {
        String payload = recommendationOutboxRepository.findByEventId(eventId).orElseThrow().getPayload();
        return jsonMapper.readValue(payload, AiRecipeRecommendationRequest.class);
    }

    @Transactional
    public void markSent(String eventId) {
        recommendationOutboxRepository.findByEventId(eventId).orElseThrow().markSent();
    }

    public record Event(Long sequence, String eventId, RecommendationOutbox.Status status, AiRecipeRecommendationRequest request) {}

    @Transactional(readOnly = true)
    public List<Event> batch(long lastScannedOutboxId, int batchSize) {
        return recommendationOutboxRepository.findByIdGreaterThanOrderByIdAsc(lastScannedOutboxId, PageRequest.of(0, batchSize)).stream().map(this::event).toList();
    }

    @Transactional(readOnly = true)
    public List<Event> matching(String requestId) {
        return recommendationOutboxRepository.findByRequestIdOrderByIdAsc(requestId).stream().map(this::event).toList();
    }

    private Event event(RecommendationOutbox recommendationOutbox) {
        return new Event(recommendationOutbox.getId(), recommendationOutbox.getEventId(), recommendationOutbox.getStatus(),
                jsonMapper.readValue(recommendationOutbox.getPayload(), AiRecipeRecommendationRequest.class));
    }
}
