package com.dameokja.backend.notification.infrastructure.kafka;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.application.MembershipNotificationProcessor;
import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class MembershipKafkaListener {
    private static final TypeReference<EventEnvelope<MembershipChangeEvent>> TYPE = new TypeReference<>() {};
    private final JsonMapper mapper;
    private final MembershipNotificationProcessor processor;

    @KafkaListener(id = "membership-notifications", topics = "${membership.kafka.topic:local.dameokja.refrigerator.membership.v1}",
            groupId = "${membership.kafka.group-id:local.dameokja.membership-notification.v1}",
            containerFactory = "membershipNotificationKafkaListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope<MembershipChangeEvent> envelope = mapper.readValue(record.value(), TYPE);
        // 프록시 서비스가 DB 커밋을 끝낸 뒤 반환해야 RECORD offset을 진행할 수 있다.
        processor.process(record.key(), envelope);
    }
}
