package com.dameokja.backend.analysis.application.outbox;

import com.dameokja.backend.global.outbox.OutboxPublicationBatch;
import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisOutboxWorker {
    private final AnalysisOutboxQueue analysisOutboxQueue;
    private final AnalysisKafkaPublisher analysisKafkaPublisher;

    public void publishBatch() {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        OutboxPublicationBatch.publish(analysisOutboxQueue.leaseBatch(), this::publish);
    }

    private void publish(AnalysisOutbox outbox) {
        OutboxPublicationBatch.deliver(outbox, analysisKafkaPublisher::publish, analysisOutboxQueue::markPublished, this::retry);
    }

    private void retry(AnalysisOutbox outbox, Exception exception) {
        log.warn("분석 이벤트 발행 또는 완료 기록 실패. 재시도합니다. outboxId={}", outbox.getId(), exception);
        analysisOutboxQueue.retryLater(outbox);
    }
}
