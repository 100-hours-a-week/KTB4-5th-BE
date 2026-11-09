package com.dameokja.backend.notification.infrastructure.kafka;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.application.ExpirationCheckProcessor;
import com.dameokja.backend.notification.domain.ExpirationCheck;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.expiration-kafka.enabled", havingValue = "true")
public class ExpirationCheckKafkaListener {
    private static final TypeReference<EventEnvelope<ExpirationCheck>> TYPE = new TypeReference<>() {};
    private final JsonMapper mapper;
    private final ExpirationCheckProcessor processor;

    @KafkaListener(id = "expiration-checks", topics = "${notification.expiration-kafka.topic}",
            groupId = "${notification.expiration-kafka.group-id}", containerFactory = "expirationCheckListenerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        // DB 커밋을 끝낸 뒤 반환해야 RECORD offset을 진행할 수 있다.
        processor.process(record.key(), mapper.readValue(record.value(), TYPE));
    }
}
