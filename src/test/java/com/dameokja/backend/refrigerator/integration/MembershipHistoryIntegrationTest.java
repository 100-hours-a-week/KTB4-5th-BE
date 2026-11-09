package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.application.membership.MembershipHistoryService;
import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import com.dameokja.backend.refrigerator.domain.MembershipChangeType;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "membership.kafka.enabled=true")
class MembershipHistoryIntegrationTest extends ServiceIntegrationTest {
    @Autowired private MembershipService memberships;
    @Autowired private InvitationService invitations;
    @Autowired private MembershipHistoryService history;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void preservesOrderedChangesAndOccurrenceDetailsAcrossMemberships() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(member.userId(), code);
        memberships.leave(member.userId(), owner.refrigeratorId());
        memberships.join(member.userId(), code);
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
        });
        assertThat(history.original(owner.refrigeratorId(), 1)).isEqualTo(events.getFirst());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class)).isZero();
    }

    @Test
    void rollsBackTheEventAndReusesTheNextSequenceWhenTheJoinRollsBack() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            memberships.join(member.userId(), code);
            status.setRollbackOnly();
        });

        assertThat(history.nextBatch(owner.refrigeratorId(), 10)).isEmpty();
        memberships.join(member.userId(), code);
        assertThat(history.nextBatch(owner.refrigeratorId(), 10)).extracting(MembershipChangeEvent::sequence).containsExactly(1L);
    }

    @Test
    void readsTheFirstUnsentChangeInSequenceRatherThanInMembershipRowOrder() {
        Fixture owner = ownerFixture("Owner");
        Fixture first = ownerFixture("First");
        Fixture second = ownerFixture("Second");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        memberships.join(first.userId(), code);
        memberships.join(second.userId(), code);
        memberships.leave(second.userId(), owner.refrigeratorId());
        memberships.leave(first.userId(), owner.refrigeratorId());
        jdbcTemplate.update("INSERT INTO membership_transmissions(refrigerator_id, last_sequence) VALUES (?, 2)", owner.refrigeratorId());

        assertThat(history.nextBatch(owner.refrigeratorId(), 1)).extracting(MembershipChangeEvent::sequence).containsExactly(3L);
    }
}
