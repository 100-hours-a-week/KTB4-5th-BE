package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeJobResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationRequest;
import com.dameokja.backend.recipe.infrastructure.kafka.RecommendationCompletedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class RecommendationKafkaCompletionService {
    private final RecommendationOutboxRepository recommendationOutboxRepository;
    private final RecommendationCompletionService recommendationCompletionService;
    private final JsonMapper jsonMapper;

    @Transactional
    public void receive(RecommendationCompletedPayload completionPayload) {
        RecommendationOutbox requestOutboxEvent = recommendationOutboxRepository.findFirstByRequestIdAndMessageBodyIsNotNullOrderByIdAsc(completionPayload.requestId())
                .orElseThrow(() -> new IllegalArgumentException("접수된 Kafka 추천 요청이 없습니다."));
        AiRecipeRecommendationRequest recommendationRequest = jsonMapper.readValue(requestOutboxEvent.getPayload(), AiRecipeRecommendationRequest.class);
        AiRecipeJobResponse recommendationResponse = completionPayload.toResponse();
        try {
            // Kafka는 사전 접수한 AI jobId가 없으므로 저장한 요청의 식별 정보와 결과를 대조한다.
            recommendationResponse.validateForRequest(recommendationRequest);
        } catch (RestClientException exception) {
            throw new IllegalArgumentException("완료 이벤트가 접수한 추천 요청과 일치하지 않습니다.", exception);
        }
        recommendationCompletionService.apply(recommendationResponse);
    }
}
