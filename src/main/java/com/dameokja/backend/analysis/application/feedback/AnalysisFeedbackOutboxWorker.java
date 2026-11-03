package com.dameokja.backend.analysis.application.feedback;

import com.dameokja.backend.analysis.application.outbox.AnalysisPublicationBatch;
import com.dameokja.backend.analysis.domain.AnalysisFeedback;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisFeedbackSubmittedPayload;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisKafkaPublisher;
import com.dameokja.backend.global.common.EventEnvelope;
import java.util.concurrent.ExecutionException;
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
        if (Thread.currentThread().isInterrupted()) { return; }
        analysisFeedbackOutbox.failExpired();
        AnalysisPublicationBatch.publish(analysisFeedbackOutbox.leaseDue(), leasedFeedback ->
                analysisFeedbackOutbox.findLeased(leasedFeedback).ifPresent(analysisFeedback -> send(leasedFeedback, analysisFeedback)));
    }

    private void send(LeasedFeedback leasedFeedback, AnalysisFeedback analysisFeedback) {
        try {
            EventEnvelope<AnalysisFeedbackSubmittedPayload> analysisFeedbackSubmittedEvent =
                    objectMapper.readValue(analysisFeedback.getFeedbackBody(), new TypeReference<EventEnvelope<AnalysisFeedbackSubmittedPayload>>() {});
            analysisKafkaPublisher.publishFeedback(analysisFeedbackSubmittedEvent).get();
            analysisFeedbackOutbox.finish(leasedFeedback);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (JacksonException exception) {
            log.error("저장된 피드백 이벤트를 읽을 수 없습니다. analysisFeedbackId={}", leasedFeedback.id(), exception);
            analysisFeedbackOutbox.fail(leasedFeedback, AnalysisFeedbackErrorCode.INVALID_EVENT);
        } catch (ExecutionException | RuntimeException exception) {
            log.warn("피드백 Kafka 발행 또는 완료 기록 실패. analysisFeedbackId={}", leasedFeedback.id(), exception);
            analysisFeedbackOutbox.retryLater(leasedFeedback, AnalysisFeedbackErrorCode.PUBLISH_FAILED);
        }
    }
}
