package com.dameokja.backend.recipe.infrastructure.kafka;

import com.dameokja.backend.recipe.application.RecommendationOutboxQueue.Lease;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class RecommendationKafkaPublisher {
    private final KafkaTemplate<String, JsonNode> kafkaTemplate;
    private final JsonMapper jsonMapper;

    public CompletableFuture<Void> publish(Lease outboxLease) {
        return kafkaTemplate.send(outboxLease.topic(), outboxLease.key(), jsonMapper.readTree(outboxLease.body())).thenApply(sendResult -> null);
    }
}
