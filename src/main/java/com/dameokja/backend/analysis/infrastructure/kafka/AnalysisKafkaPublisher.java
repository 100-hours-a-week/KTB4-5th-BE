package com.dameokja.backend.analysis.infrastructure.kafka;

import com.dameokja.backend.analysis.domain.AnalysisOutbox;
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
        return send(analysisOutbox.getTopic(), analysisOutbox.getMessageKey(), objectMapper.readTree(analysisOutbox.getMessageBody()));
    }

    public CompletableFuture<Void> publishFeedback(AnalysisFeedbackSubmittedEvent analysisFeedbackSubmittedEvent) {
        return send(analysisKafkaProperties.feedbackTopic(), analysisFeedbackSubmittedEvent.payload().userId().toString(), objectMapper.valueToTree(analysisFeedbackSubmittedEvent));
    }

    private CompletableFuture<Void> send(String topic, String messageKey, JsonNode messageBody) {
        return kafkaTemplate.send(topic, messageKey, messageBody).thenApply(sendResult -> null);
    }
}
