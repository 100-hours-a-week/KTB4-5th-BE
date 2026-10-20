package com.dameokja.backend.refrigerator.integration;

import static org.assertj.core.api.Assertions.*;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.*;
import com.dameokja.backend.refrigerator.domain.*;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.withdrawal.UserWithdrawalService;
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
    void leaveRestoresPersonalAndPreservesBothInventoriesAndAllowsRejoin() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        seedIngredient(owner.refrigeratorId());
        seedIngredient(member.refrigeratorId());
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        membershipService.join(member.userId(), code);
        assertThat(membershipService.leave(member.userId(), owner.refrigeratorId())).isEqualTo(member.refrigeratorId());
        assertThat(activeId(member.userId())).isEqualTo(member.refrigeratorId());
        assertError(RefrigeratorExceptionCode.ACCESS_DENIED, () -> access.validateReadAccess(member.userId(), owner.refrigeratorId()));
        assertError(RefrigeratorExceptionCode.ACCESS_DENIED, () -> access.validateWriteAccess(member.userId(), owner.refrigeratorId()));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isEqualTo(2);
        assertThat(queries.getMembers(owner.userId(), owner.refrigeratorId()).memberNum()).isOne();
        membershipService.join(member.userId(), code);
        assertThat(queries.getMembers(owner.userId(), owner.refrigeratorId()).memberNum()).isEqualTo(2);
    }

    @Test
    void kickRestoresPersonalAndNonOwnerCannotKick() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        membershipService.join(member.userId(), code);
        assertError(RefrigeratorExceptionCode.OWNER_ONLY, () -> membershipService.kick(member.userId(), owner.refrigeratorId(), owner.userId()));
        membershipService.kick(owner.userId(), owner.refrigeratorId(), member.userId());
        assertThat(activeId(member.userId())).isEqualTo(member.refrigeratorId());
        assertError(RefrigeratorExceptionCode.ACCESS_DENIED, () -> access.validateReadAccess(member.userId(), owner.refrigeratorId()));
    }

    @Test
    void oppositeRemovalRequestsFinishWithoutDeadlockAndKeepBothPersonalSpaces() throws Exception {
        Fixture first = ownerFixture("First");
        Fixture second = ownerFixture("Second");
        List<Object> results = concurrently(List.of(
                () -> {
                    membershipService.kick(first.userId(), first.refrigeratorId(), second.userId());
                    return null;
                },
                () -> {
                    membershipService.kick(second.userId(), second.refrigeratorId(), first.userId());
                    return null;
                }));
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(RefrigeratorExceptionCode.MEMBER_NOT_FOUND)));
        assertThat(activeId(first.userId())).isEqualTo(first.refrigeratorId());
        assertThat(activeId(second.userId())).isEqualTo(second.refrigeratorId());
    }

    @Test
    void missingRemovalTargetAndRefrigeratorUseSpecifiedNotFoundCode() {
        Fixture owner = ownerFixture("Owner");
        Fixture target = ownerFixture("Target");
        assertError(RefrigeratorExceptionCode.MEMBER_NOT_FOUND,
                () -> membershipService.kick(owner.userId(), owner.refrigeratorId(), Long.MAX_VALUE));
        assertError(RefrigeratorExceptionCode.MEMBER_NOT_FOUND,
                () -> membershipService.kick(owner.userId(), Long.MAX_VALUE, target.userId()));
        assertThat(activeId(owner.userId())).isEqualTo(owner.refrigeratorId());
        assertThat(activeId(target.userId())).isEqualTo(target.refrigeratorId());
    }

    @Test
    void ownerCannotLeaveOrKickSelf() {
        Fixture owner = ownerFixture("Owner");
        assertError(RefrigeratorExceptionCode.OWNER_CANNOT_LEAVE, () -> membershipService.leave(owner.userId(), owner.refrigeratorId()));
        assertError(RefrigeratorExceptionCode.OWNER_ONLY, () -> membershipService.kick(owner.userId(), owner.refrigeratorId(), owner.userId()));
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

    @Test
    void ownerWithdrawalTransfersOwnershipAndPreservesSharedSpace() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        membershipService.join(member.userId(), code);
        withdrawal.withdraw(owner.userId());
        MemberListView list = queries.getMembers(member.userId(), owner.refrigeratorId());
        assertThat(list.memberNum()).isOne();
        assertThat(list.members().getFirst().role()).isEqualTo("owner");
        assertThat(refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow().getDeletedAt()).isNull();
        assertError(RefrigeratorExceptionCode.OWNER_CANNOT_LEAVE, () -> membershipService.leave(member.userId(), owner.refrigeratorId()));
    }

    @Test
    void failedPersonalRestoreRollsBackTermination() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        membershipService.join(member.userId(), code);
        jdbcTemplate.execute("CREATE TRIGGER reject_restore BEFORE UPDATE ON refrigerator_members FOR EACH ROW "
                + "BEGIN IF NEW.role='OWNER' AND NEW.is_active=1 AND OLD.is_active=0 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='restore failure'; END IF; END");
        try {
            assertThatThrownBy(() -> membershipService.leave(member.userId(), owner.refrigeratorId()))
                    .hasStackTraceContaining("restore failure");
            assertThat(activeId(member.userId())).isEqualTo(owner.refrigeratorId());
            assertThat(queries.getMembers(member.userId(), owner.refrigeratorId()).memberNum()).isEqualTo(2);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_restore");
        }
    }

    @Test
    void sharedParticipationKeepsPersonalNotificationTargetAndEndedMemberCannotReceivePush() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        String code = invitations.issueCode(owner.userId(), owner.refrigeratorId()).code();
        membershipService.join(member.userId(), code);
        assertThat(refrigeratorMemberRepository.findNotificationTargetRefrigeratorIds())
                .contains(owner.refrigeratorId(), member.refrigeratorId());
        assertThat(refrigeratorMemberRepository.findNotificationRecipients(member.refrigeratorId()))
                .extracting(relation -> relation.getUser().getId()).containsExactly(member.userId());
        jdbcTemplate.update("INSERT INTO notification_preferences(user_id,type,is_enabled) VALUES (?,'EXPIRATION',1)", member.userId());
        assertThat(push.canReceiveExpirationPush(member.userId(), owner.refrigeratorId())).isTrue();
        membershipService.kick(owner.userId(), owner.refrigeratorId(), member.userId());
        assertThat(push.canReceiveExpirationPush(member.userId(), owner.refrigeratorId())).isFalse();
        assertThat(refrigeratorMemberRepository.findNotificationRecipients(owner.refrigeratorId()))
                .extracting(relation -> relation.getUser().getId()).containsExactly(owner.userId());
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

    private void seedIngredient(Long id) {
        jdbcTemplate.update("INSERT INTO ingredients (refrigerator_id,name,category,storage_type,measure_type,quantity,weight_unit,expiration_date,registration_source,owner_id) "
                + "VALUES (?,'carrot','VEGETABLE','REFRIGERATED','COUNT',1,'NONE','2026-10-20','DIRECT',"
                + "(SELECT user_id FROM refrigerator_members WHERE refrigerator_id=? AND role='OWNER' LIMIT 1))", id, id);
    }
}
