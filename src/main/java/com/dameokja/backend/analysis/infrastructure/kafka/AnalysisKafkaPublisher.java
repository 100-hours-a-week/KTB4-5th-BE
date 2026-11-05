package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.domain.AnalysisOutbox;
import com.dameokja.backend.global.common.EventEnvelope;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AnalysisKafkaPublisher {
    private final KafkaTemplate<String, JsonNode> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisKafkaProperties analysisKafkaProperties;

    public CompletableFuture<Void> publish(AnalysisOutbox analysisOutbox) {
        return kafkaTemplate.send(analysisOutbox.getTopic(), analysisOutbox.getMessageKey(), objectMapper.readTree(analysisOutbox.getMessageBody()))
                .thenApply(sendResult -> null);
    }

    public CompletableFuture<Void> publishFeedback(EventEnvelope<AnalysisFeedbackSubmittedPayload> event) {
        return kafkaTemplate.send(analysisKafkaProperties.feedbackTopic(), event.payload().userId().toString(), objectMapper.valueToTree(event))
                .thenApply(sendResult -> null);
    }
}
