package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.*;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.*;
import com.dameokja.backend.refrigerator.domain.*;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.UserWithdrawalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MembershipIntegrationTest extends ServiceIntegrationTest {
    @Autowired InvitationService invitations;
    @Autowired MembershipService membershipService;
    @Autowired RefrigeratorService queries;
    @Autowired RefrigeratorAccessService access;
    @Autowired UserWithdrawalService withdrawal;
    @Autowired com.dameokja.backend.push.infrastructure.PushNotificationRepository push;

    @Test
    void joinsWithOneCodeRepeatedlyWithoutConsumingOrExtendingIt() {
        Fixture owner = ownerFixture("Owner");
        Fixture first = ownerFixture("First");
        Fixture second = ownerFixture("Second");
        InvitationView code = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        membershipService.join(first.userId(), code.code());
        membershipService.join(second.userId(), code.code());
        MemberListView list = queries.getMembers(first.userId(), owner.refrigeratorId());
        assertThat(list.memberNum()).isEqualTo(3);
        assertThat(list.memberMax()).isEqualTo(150);
        assertThat(list.members()).extracting(MemberListView.MemberView::role).containsExactly("owner", "member", "member");
        assertThat(invitations.getValidCode(owner.userId(), owner.refrigeratorId())).isEqualTo(code);
        assertThat(activeId(first.userId())).isEqualTo(owner.refrigeratorId());
        assertThat(refrigeratorRepository.findById(first.refrigeratorId())).isPresent();
    }

    @Test
    void reissuedCodeRejectsOldCodeWithoutChangingPersonalMembership() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        InvitationView old = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        InvitationView current = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        assertError(RefrigeratorExceptionCode.EXPIRED_INVITE, () -> membershipService.join(member.userId(), old.code()));
        assertThat(activeId(member.userId())).isEqualTo(member.refrigeratorId());
        membershipService.join(member.userId(), current.code());
        assertThat(activeId(member.userId())).isEqualTo(owner.refrigeratorId());
    }

    @Test
    void rejectsAtExpirationBoundary() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        InvitationView code = invitations.issueCode(owner.userId(), owner.refrigeratorId());
        clock.set("2026-09-17T03:05:00Z");
        assertError(RefrigeratorExceptionCode.EXPIRED_INVITE, () -> membershipService.join(member.userId(), code.code()));
        assertThat(activeId(member.userId())).isEqualTo(member.refrigeratorId());
    }

    @Test
    void rollbackAfterPersonalDeactivationRestoresOriginalState() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        jdbcTemplate.execute("CREATE TRIGGER reject_shared_join BEFORE INSERT ON refrigerator_members FOR EACH ROW "
                + "BEGIN IF NEW.role='MEMBER' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='join failure'; END IF; END");
        try {
            assertThatThrownBy(() -> membershipService.join(member.userId(), code)).hasStackTraceContaining("join failure");
            assertThat(activeId(member.userId())).isEqualTo(member.refrigeratorId());
            assertThat(queries.getMembers(owner.userId(), owner.refrigeratorId()).memberNum()).isOne();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_shared_join");
        }
    }

    private Long activeId(Long userId) {
        return refrigeratorMemberRepository.findActiveRefrigeratorId(userId).orElseThrow();
    }

    private void assertError(RefrigeratorExceptionCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getExceptionCode()).isEqualTo(code));
    }

}
