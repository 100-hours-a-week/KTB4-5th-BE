package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.*;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.*;
import com.dameokja.backend.refrigerator.domain.*;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.UserWithdrawalService;
import java.util.List;
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
    void concurrentJoinersCannotTakeTheLastPlaceTwice() throws Exception {
        Fixture owner = ownerFixture("Owner");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        seedMembers(owner.refrigeratorId(), 148);
        Fixture third = ownerFixture("Third");
        Fixture fourth = ownerFixture("Fourth");
        List<Object> results = concurrently(List.of(() -> membershipService.join(third.userId(), code),
                () -> membershipService.join(fourth.userId(), code)));
        assertThat(results.stream().filter(owner.refrigeratorId()::equals)).hasSize(1);
        assertThat(results.stream().filter(CustomException.class::isInstance).map(CustomException.class::cast)
                .map(CustomException::getExceptionCode)).containsExactly(RefrigeratorExceptionCode.FULL);
        assertThat(queries.getMembers(owner.userId(), owner.refrigeratorId()).memberNum()).isEqualTo(150);
        Fixture rejectedMember = results.getFirst() instanceof CustomException ? third : fourth;
        assertThat(activeId(rejectedMember.userId())).isEqualTo(rejectedMember.refrigeratorId());
    }

    @Test
    void oneUserCannotJoinTwoRefrigeratorsConcurrently() throws Exception {
        Fixture first = ownerFixture("First");
        Fixture second = ownerFixture("Second");
        Fixture joining = ownerFixture("Joining");
        String a = invitations.issueCode(first.userId(), first.refrigeratorId()).code();
        String b = invitations.issueCode(second.userId(), second.refrigeratorId()).code();
        List<Object> results = concurrently(List.of(() -> membershipService.join(joining.userId(), a),
                () -> membershipService.join(joining.userId(), b)));
        assertThat(results.stream().filter(Long.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(CustomException.class::isInstance).map(CustomException.class::cast)
                .map(CustomException::getExceptionCode)).containsExactly(RefrigeratorExceptionCode.ALREADY_SHARING);
        assertThat(refrigeratorMemberRepository.findActiveRefrigeratorIdsByUserId(joining.userId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refrigerator_members WHERE user_id=? AND role='MEMBER' AND ended_at IS NULL",
                Integer.class, joining.userId())).isOne();
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

    private void seedMembers(Long refrigeratorId, int count) {
        Refrigerator refrigerator = refrigeratorRepository.findById(refrigeratorId).orElseThrow();
        for (int index = 0; index < count; index++) {
            join(user("Seed" + index), refrigerator, true, false);
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
