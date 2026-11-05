package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.application.membership.MembershipHistoryService;
import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import com.dameokja.backend.refrigerator.infrastructure.kafka.MembershipKafkaSender;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {"membership.kafka.enabled=true", "membership.kafka.transport-enabled=true",
        "membership.kafka.scheduling-enabled=false"})
class MembershipKafkaSenderIntegrationTest extends ServiceIntegrationTest {
    private static final TypeReference<EventEnvelope<MembershipChangeEvent>> ENVELOPE = new TypeReference<>() {};
    @Autowired private MembershipKafkaSender sender;
    @Autowired private MembershipHistoryService history;
    @Autowired private MembershipService memberships;
    @Autowired private InvitationService invitations;
    @Autowired private JsonMapper mapper;

    @AfterEach
    void releaseSenderOwnership() {
        sender.close();
    }

    @Test
    void sendsEventsInOrderAndDoesNotResendConfirmedEvents() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = join(owner);
        memberships.leave(member.userId(), owner.refrigeratorId());
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        try (var broker = captureSends(sent)) {
            sender.sendPending();
            sender.sendPending();
        }

        assertThat(sent).extracting(ProducerRecord::key).containsExactly(owner.refrigeratorId().toString(), owner.refrigeratorId().toString());
        assertThat(sent.stream().map(this::envelope).map(EventEnvelope::payload))
                .extracting(MembershipChangeEvent::sequence).containsExactly(1L, 2L);
        assertThat(history.nextBatch(owner.refrigeratorId(), 10)).isEmpty();
    }

    @Test
    void retriesWithTheSameIdentityWhenTheTransmissionCheckpointFails() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = join(owner);
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        jdbcTemplate.execute("CREATE TRIGGER reject_membership_checkpoint BEFORE INSERT ON membership_transmissions "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='checkpoint unavailable'");
        try (var broker = captureSends(sent)) {
            try {
                sender.sendPending();
                verify(broker.constructed().getFirst()).commitTransaction();
                assertThat(history.nextBatch(owner.refrigeratorId(), 10)).hasSize(1);
                assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(member.userId())).contains(owner.refrigeratorId());
            } finally {
                jdbcTemplate.execute("DROP TRIGGER reject_membership_checkpoint");
            }
            sender.sendPending();
        }

        assertThat(sent).hasSize(2);
        assertThat(envelope(sent.getLast())).isEqualTo(envelope(sent.getFirst()));
        assertThat(history.nextBatch(owner.refrigeratorId(), 10)).isEmpty();
    }

    private Fixture join(Fixture owner) {
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(member.userId(), code);
        return member;
    }

    private EventEnvelope<MembershipChangeEvent> envelope(ProducerRecord<String, String> record) {
        return mapper.readValue(record.value(), ENVELOPE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MockedConstruction<KafkaProducer> captureSends(List<ProducerRecord<String, String>> sent) {
        return mockConstruction(KafkaProducer.class, (producer, context) -> when(producer.send(any())).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            return CompletableFuture.completedFuture(null);
        }));
    }
}
