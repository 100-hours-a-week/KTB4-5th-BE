package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RecommendationOutboxService {
    private final RecommendationOutboxRepository recommendationOutboxRepository;
    private final JsonMapper jsonMapper;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final Clock clock;
    @Value("${recommendation.kafka.submission-enabled:true}")
    private boolean kafkaSubmissionEnabled;
    @Value("${recommendation.kafka.request-topic}")
    private String recommendationRequestTopic;
    @Value("${recipe.job.database-write-enabled:false}")
    private boolean databaseWriteEnabled;

    @Transactional
    public RecommendationSubmissionResult enqueue(AiRecipeRecommendationRequest recommendationRequest) {
        Refrigerator refrigerator = refrigeratorAccessService.getReferenceById(recommendationRequest.refrigeratorId());
        RecommendationOutbox recommendationOutbox = new RecommendationOutbox(UUID.randomUUID().toString(), refrigerator, recommendationRequest.requestId(),
                jsonMapper.writeValueAsString(recommendationRequest), databaseWriteEnabled);
        if (kafkaSubmissionEnabled) {
            prepareKafkaMessage(recommendationOutbox, recommendationRequest);
        }
        recommendationOutboxRepository.saveAndFlush(recommendationOutbox);
        return new RecommendationSubmissionResult(recommendationOutbox.getId(), recommendationOutbox.getEventId(), recommendationOutbox.getRequestId(),
                recommendationRequest.refrigeratorId(), recommendationOutbox.getCreatedAt());
    }

    private void prepareKafkaMessage(RecommendationOutbox recommendationOutbox, AiRecipeRecommendationRequest recommendationRequest) {
        RecommendationRequestedPayload payload = new RecommendationRequestedPayload(clock.instant().toString(), recommendationRequest);
        EventEnvelope<RecommendationRequestedPayload> event = new EventEnvelope<>(recommendationOutbox.getEventId(), payload);
        recommendationOutbox.prepareKafka(recommendationRequestTopic, recommendationRequest.userId().toString(), jsonMapper.writeValueAsString(event),
                BusinessTime.now(clock));
    }

    private record RecommendationRequestedPayload(String occurredAt, @JsonUnwrapped AiRecipeRecommendationRequest request) {}

    @Transactional(readOnly = true)
    public AiRecipeRecommendationRequest request(String eventId) {
        String payload = recommendationOutboxRepository.findByEventId(eventId).orElseThrow().getPayload();
        return jsonMapper.readValue(payload, AiRecipeRecommendationRequest.class);
    }

    @Transactional
    public void markSent(String eventId) {
        recommendationOutboxRepository.lockByEventId(eventId).orElseThrow().markSent();
    }

    public record Event(Long sequence, String eventId, RecommendationOutbox.Status status, AiRecipeRecommendationRequest request) {}

    @Transactional(readOnly = true)
    public List<Event> batch(long lastScannedOutboxId, int batchSize) {
        return recommendationOutboxRepository.findByIdGreaterThanAndMessageBodyIsNullOrderByIdAsc(lastScannedOutboxId, PageRequest.of(0, batchSize))
                .stream().map(this::event).toList();
    }

    @Transactional(readOnly = true)
    public List<Event> matching(String requestId) {
        return recommendationOutboxRepository.findByRequestIdOrderByIdAsc(requestId).stream().map(this::event).toList();
    }

    @Transactional(readOnly = true)
    public List<Event> matchingHttp(String requestId) {
        return recommendationOutboxRepository.findByRequestIdAndMessageBodyIsNullOrderByIdAsc(requestId).stream().map(this::event).toList();
    }

    private Event event(RecommendationOutbox recommendationOutbox) {
        return new Event(recommendationOutbox.getId(), recommendationOutbox.getEventId(), recommendationOutbox.getStatus(),
                jsonMapper.readValue(recommendationOutbox.getPayload(), AiRecipeRecommendationRequest.class));
    }
}
