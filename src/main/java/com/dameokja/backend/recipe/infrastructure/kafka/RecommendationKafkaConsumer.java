package com.dameokja.backend.recipe.infrastructure.kafka;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.recipe.application.RecommendationKafkaCompletionService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "recommendation.kafka.completion-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RecommendationKafkaConsumer {
    private final RecommendationKafkaCompletionService recommendationKafkaCompletionService;

    @KafkaListener(id = "recommendation-completed", idIsGroup = false, topics = "${recommendation.kafka.completion-topic}",
            groupId = "${recommendation.kafka.completion-group-id}", containerFactory = "recommendationCompletionKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, EventEnvelope<RecommendationCompletedPayload>> completionRecord) {
        if (completionRecord.value() == null) {
            throw new IllegalArgumentException("완료 이벤트가 없습니다.");
        }
        RecommendationCompletedPayload completionPayload = completionRecord.value().payload();
        if (!completionPayload.userId().toString().equals(completionRecord.key())) {
            throw new IllegalArgumentException("Kafka key는 payload.userId와 같아야 합니다.");
        }
        try {
            // DB 커밋이 끝난 뒤 반환해야 RECORD 모드에서 offset을 넘길 수 있다.
            recommendationKafkaCompletionService.receive(completionPayload);
        } catch (CustomException exception) {
            if (exception.getExceptionCode().getStatus() == HttpStatus.NOT_FOUND) {
                throw new IllegalArgumentException("추천 완료 이벤트의 대상이 없습니다.", exception);
            }
            throw exception;
        }
    }
}
