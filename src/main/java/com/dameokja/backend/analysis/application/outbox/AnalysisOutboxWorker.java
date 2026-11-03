package com.dameokja.backend.analysis.application.outbox;

import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import java.util.concurrent.ExecutionException;
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
        if (Thread.currentThread().isInterrupted()) { return; }
        AnalysisPublicationBatch.publish(analysisOutboxQueue.leaseBatch(), this::publish);
    }

    private void publish(AnalysisOutbox analysisOutbox) {
        try {
            // 일시 장애의 재시도와 최종 전달 제한은 Kafka producer가 관리한다.
            analysisKafkaPublisher.publish(analysisOutbox).get();
            analysisOutboxQueue.markPublished(analysisOutbox);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException publishException) {
            log.warn("분석 이벤트 발행 또는 완료 기록 실패. 재시도합니다. outboxId={}", analysisOutbox.getId(), publishException);
            analysisOutboxQueue.retryLater(analysisOutbox);
        }
    }
}
