package com.dameokja.backend.recipe.infrastructure.kafka;

import com.dameokja.backend.global.outbox.OutboxPublicationBatch;
import com.dameokja.backend.recipe.infrastructure.CookingOutboxRepository;
import com.dameokja.backend.recipe.infrastructure.CookingOutboxRepository.PendingMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@ConditionalOnProperty(name = "cooking.kafka.publish-enabled", havingValue = "true")
public class CookingOutboxPublisher {
    private final CookingOutboxRepository outbox;
    private final KafkaTemplate<Object, Object> kafka;
    private final JsonMapper mapper;
    private final String topic;

    public CookingOutboxPublisher(CookingOutboxRepository outbox,
                                  @Qualifier("kafkaTemplate") KafkaTemplate<Object, Object> kafka,
                                  JsonMapper mapper, @Value("${cooking.kafka.applied-topic}") String topic) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${cooking.kafka.poll-interval:5s}", scheduler = "cookingOutboxTaskScheduler")
    public void publishPending() {
        OutboxPublicationBatch.publish(outbox.pending(), this::publish);
    }

    private void publish(PendingMessage message) {
        if (!outbox.claim(message.cookingRecordId())) {
            return;
        }
        OutboxPublicationBatch.deliver(message,
                pending -> kafka.send(topic, pending.key(), mapper.readTree(pending.body())),
                pending -> outbox.markPublished(pending.cookingRecordId()),
                (pending, error) -> log.warn("요리 적용 발행을 확인하지 못했습니다. 선점 만료 후 재시도합니다. cookingRecordId={}",
                        pending.cookingRecordId(), error));
    }
}
