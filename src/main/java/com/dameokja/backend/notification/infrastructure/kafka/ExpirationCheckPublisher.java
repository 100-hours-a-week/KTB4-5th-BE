package com.dameokja.backend.notification.infrastructure.kafka;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.global.outbox.OutboxPublicationBatch;
import com.dameokja.backend.notification.domain.ExpirationCheck;
import com.dameokja.backend.notification.infrastructure.ExpirationCheckRepository;
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
@ConditionalOnProperty(name = "notification.expiration-kafka.enabled", havingValue = "true")
public class ExpirationCheckPublisher {
    private final ExpirationCheckRepository checks;
    private final KafkaTemplate<Object, Object> kafka;
    private final JsonMapper mapper;
    private final String topic;

    public ExpirationCheckPublisher(ExpirationCheckRepository checks,
                                    @Qualifier("kafkaTemplate") KafkaTemplate<Object, Object> kafka,
                                    JsonMapper mapper, @Value("${notification.expiration-kafka.topic}") String topic) {
        this.checks = checks;
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${notification.expiration-kafka.poll-interval:5s}", scheduler = "expirationCheckTaskScheduler")
    public void publishPending() {
        OutboxPublicationBatch.publish(checks.pending(), this::publish);
    }

    private void publish(EventEnvelope<ExpirationCheck> message) {
        if (!checks.claim(message.payload())) {
            return;
        }
        OutboxPublicationBatch.deliver(message,
                event -> kafka.send(topic, event.payload().kafkaKey(), mapper.valueToTree(event)),
                event -> checks.markPublished(event.payload()),
                (event, error) -> log.warn("판정 요청 발행을 확인하지 못했습니다. 선점 만료 후 재시도합니다. messageId={}",
                        event.messageId(), error));
    }
}
