package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.application.MembershipNotificationProcessor;
import com.dameokja.backend.notification.infrastructure.kafka.MembershipKafkaListener;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.application.membership.MembershipHistoryService;
import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import com.dameokja.backend.refrigerator.domain.MembershipChangeType;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = "membership.kafka.enabled=true")
class MembershipKafkaProcessingIntegrationTest extends ServiceIntegrationTest {
    @Autowired private MembershipService memberships;
    @Autowired private InvitationService invitations;
    @Autowired private MembershipHistoryService history;
    @Autowired private MembershipNotificationProcessor processor;
    @Autowired private MembershipKafkaListener listener;
    @Autowired private JsonMapper mapper;

    @Test
    void notificationDatabaseFailureCannotUndoCommittedParticipation() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        jdbcTemplate.execute("CREATE TRIGGER reject_membership_notification BEFORE INSERT ON notifications FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='notification unavailable'");
        try {
            memberships.join(member.userId(), code);
            MembershipChangeEvent event = history.nextBatch(owner.refrigeratorId(), 10).getFirst();
            assertThat(count("notifications")).isZero();
            assertThatThrownBy(() -> process(event)).isInstanceOf(RuntimeException.class);
            assertThat(refrigeratorMemberRepository.findActiveRefrigeratorId(member.userId())).contains(owner.refrigeratorId());
            assertThat(count("membership_notification_progress")).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_membership_notification");
        }
        process(history.nextBatch(owner.refrigeratorId(), 10).getFirst());
        assertThat(count("notifications")).isOne();
    }

    @Test
    void rejoiningPreservesOrderedBusinessChangesAndOriginalRecipients() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(member.userId(), code);
        clock.set("2026-09-17T03:01:00Z");
        memberships.leave(member.userId(), owner.refrigeratorId());
        clock.set("2026-09-17T03:02:00Z");
        memberships.join(member.userId(), code);
        clock.set("2026-09-17T03:03:00Z");
        memberships.kick(owner.userId(), owner.refrigeratorId(), member.userId());
        jdbcTemplate.update("UPDATE users SET nickname='Renamed' WHERE user_id=?", member.userId());

        List<MembershipChangeEvent> events = history.nextBatch(owner.refrigeratorId(), 10);
        assertThat(events).extracting(MembershipChangeEvent::sequence).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events).extracting(MembershipChangeEvent::type).containsExactly(
                MembershipChangeType.JOINED, MembershipChangeType.LEFT, MembershipChangeType.JOINED, MembershipChangeType.KICKED);
        assertThat(events.getFirst().membershipId()).isNotEqualTo(events.get(2).membershipId());
        assertThat(events).allSatisfy(event -> {
            assertThat(event.body()).contains("Member");
            assertThat(event.recipientUserIds()).containsExactly(owner.userId());
            process(event);
        });
        assertThat(count("notifications")).isEqualTo(4);
        assertThat(count("push_notifications")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT MIN(created_at) FROM notifications", java.time.LocalDateTime.class))
                .isEqualTo(events.getFirst().occurredAt());
    }

    @Test
    void duplicateAfterInboxCleanupDoesNotRecreateNotification() {
        MembershipChangeEvent event = oneJoinedEvent();
        process(event);
        jdbcTemplate.update("DELETE FROM notification_recipients");
        jdbcTemplate.update("DELETE FROM notifications");
        process(event);
        assertThat(count("notifications")).isZero();
        assertThat(count("membership_notification_progress")).isOne();
    }

    @Test
    void originalMismatchAndMissingPredecessorNeverAdvanceCompletion() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        memberships.join(member.userId(), invitations.issueCode(owner.userId(), owner.refrigeratorId()).code());
        memberships.leave(member.userId(), owner.refrigeratorId());
        List<MembershipChangeEvent> events = history.nextBatch(owner.refrigeratorId(), 10);
        assertThatThrownBy(() -> process(events.getLast())).isInstanceOf(IllegalStateException.class);
        MembershipChangeEvent first = events.getFirst();
        assertThatThrownBy(() -> processor.process("wrong-key", new EventEnvelope<>(first.messageId(), first)))
                .isInstanceOf(IllegalArgumentException.class);
        MembershipChangeEvent changed = new MembershipChangeEvent(first.refrigeratorId(), first.membershipId(), first.subjectUserId(),
                first.sequence(), first.type(), first.occurredAt(), first.title(), "tampered", first.recipientUserIds());
        assertThatThrownBy(() -> process(changed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("membership_notification_progress")).isZero();
        events.forEach(this::process);
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void concurrentRedeliveryCommitsOnlyOneNotification() throws Exception {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        memberships.join(member.userId(), invitations.issueCode(owner.userId(), owner.refrigeratorId()).code());
        MembershipChangeEvent first = history.nextBatch(owner.refrigeratorId(), 10).getFirst();
        process(first);
        memberships.leave(member.userId(), owner.refrigeratorId());
        MembershipChangeEvent second = history.nextBatch(owner.refrigeratorId(), 10).getLast();
        List<Object> results = concurrently(List.of(() -> { process(second); return true; }, () -> { process(second); return true; }));
        assertThat(results).containsExactly(true, true);
        assertThat(count("notifications")).isEqualTo(2);
    }

    @Test
    void recipientsAreTakenFromOccurrenceEvenAfterTheyLeave() {
        Fixture owner = ownerFixture("Owner");
        Fixture first = ownerFixture("First");
        Fixture second = ownerFixture("Second");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(first.userId(), code);
        memberships.join(second.userId(), code);
        memberships.leave(first.userId(), owner.refrigeratorId());
        MembershipChangeEvent secondJoin = history.nextBatch(owner.refrigeratorId(), 10).get(1);
        assertThat(secondJoin.recipientUserIds()).containsExactlyInAnyOrder(owner.userId(), first.userId());
        history.nextBatch(owner.refrigeratorId(), 10).forEach(this::process);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_recipients WHERE user_id=?",
                Integer.class, first.userId())).isOne();
    }

    private MembershipChangeEvent oneJoinedEvent() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        memberships.join(member.userId(), invitations.issueCode(owner.userId(), owner.refrigeratorId()).code());
        return history.nextBatch(owner.refrigeratorId(), 10).getFirst();
    }

    private void process(MembershipChangeEvent event) {
        String payload = mapper.writeValueAsString(new EventEnvelope<>(event.messageId(), event));
        listener.receive(new ConsumerRecord<>("test.membership", 0, event.sequence() - 1, event.refrigeratorId().toString(), payload));
    }

    private int count(String table) {
        if (!List.of("notifications", "membership_notification_progress", "push_notifications").contains(table)) {
            throw new IllegalArgumentException("Unexpected table");
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
