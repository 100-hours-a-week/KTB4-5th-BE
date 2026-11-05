package com.dameokja.backend.analysis.application.feedback;

import com.dameokja.backend.global.outbox.OutboxPublicationBatch;
import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisFeedbackSubmittedPayload;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import com.dameokja.backend.global.common.EventEnvelope;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisFeedbackOutboxWorker {
    private final AnalysisFeedbackOutbox analysisFeedbackOutbox;
    private final AnalysisKafkaPublisher analysisKafkaPublisher;
    private final ObjectMapper objectMapper;

    public void sendDue() {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        analysisFeedbackOutbox.failExpired();
        OutboxPublicationBatch.publish(analysisFeedbackOutbox.leaseDue(), leasedFeedback ->
                analysisFeedbackOutbox.findLeased(leasedFeedback).ifPresent(analysisFeedback -> send(leasedFeedback, analysisFeedback)));
    }

    private void send(LeasedFeedback leasedFeedback, AnalysisFeedback analysisFeedback) {
        OutboxPublicationBatch.deliver(leasedFeedback, lease -> publish(analysisFeedback), analysisFeedbackOutbox::finish, this::handleFailure);
    }

    private CompletableFuture<Void> publish(AnalysisFeedback analysisFeedback) {
        EventEnvelope<AnalysisFeedbackSubmittedPayload> event =
                objectMapper.readValue(analysisFeedback.getMessageBody(), new TypeReference<EventEnvelope<AnalysisFeedbackSubmittedPayload>>() {});
        return analysisKafkaPublisher.publishFeedback(event);
    }

    private void handleFailure(LeasedFeedback leasedFeedback, Exception exception) {
        if (exception instanceof JacksonException) {
            log.error("저장된 피드백 이벤트를 읽을 수 없습니다. analysisFeedbackId={}", leasedFeedback.id(), exception);
            analysisFeedbackOutbox.fail(leasedFeedback, AnalysisFeedbackErrorCode.INVALID_EVENT);
            return;
        }
        log.warn("피드백 Kafka 발행 또는 완료 기록 실패. analysisFeedbackId={}", leasedFeedback.id(), exception);
        analysisFeedbackOutbox.retryLater(leasedFeedback, AnalysisFeedbackErrorCode.PUBLISH_FAILED);
    }
}
