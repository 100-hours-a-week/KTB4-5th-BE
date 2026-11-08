package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.outbox.OutboxPublicationBatch;
import com.dameokja.backend.recipe.application.RecommendationOutboxQueue.Lease;
import com.dameokja.backend.recipe.infrastructure.kafka.RecommendationKafkaPublisher;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class RecommendationOutboxWorker {
    private final RecommendationOutboxQueue recommendationOutboxQueue;
    private final RecommendationKafkaPublisher recommendationKafkaPublisher;
    @Value("${recommendation.outbox.batch-size:20}") private int batchSize;
    @Value("${recommendation.outbox.lease-duration:60s}") private Duration leaseDuration;
    @Value("${recommendation.outbox.retry-delay:5s}") private Duration retryDelay;

    public void publishBatch() {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        OutboxPublicationBatch.publish(recommendationOutboxQueue.leaseBatch(batchSize, leaseDuration), this::publish);
    }

    private void publish(Lease outboxLease) {
        OutboxPublicationBatch.deliver(outboxLease, recommendationKafkaPublisher::publish, recommendationOutboxQueue::markPublished, this::retry);
    }

    private void retry(Lease outboxLease, Exception publicationFailure) {
        log.warn("추천 이벤트 발행 또는 완료 기록 실패. 재시도합니다. outboxId={}", outboxLease.id(), publicationFailure);
        recommendationOutboxQueue.retryLater(outboxLease, retryDelay);
    }
}
