package com.dameokja.backend.refrigerator.integration;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ALREADY_SHARING;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.EXPIRED_INVITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.InvitationView;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.refrigerator.application.RefrigeratorService;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.UserWithdrawalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MembershipAvailabilityIntegrationTest extends ServiceIntegrationTest {
    @Autowired private InvitationService invitationService;
    @Autowired private MembershipService membershipService;
    @Autowired private RefrigeratorService refrigeratorService;
    @Autowired private UserWithdrawalService userWithdrawalService;

    @Test
    void invitationHistoryDoesNotPreventSoloOwnerFromJoiningAndReturning() {
        Fixture owner = ownerFixture("Owner");
        Fixture targetOwner = ownerFixture("Target");
        InvitationView originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId());
        InvitationView targetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId());
        seedIngredient(owner.refrigeratorId());
        seedIngredient(targetOwner.refrigeratorId());

        assertThat(membershipService.join(owner.userId(), targetCode.code())).isEqualTo(targetOwner.refrigeratorId());
        assertThat(activeRefrigeratorId(owner.userId())).isEqualTo(targetOwner.refrigeratorId());
        assertThat(refrigeratorRepository.findById(owner.refrigeratorId()).orElseThrow().getDeletedAt()).isNull();

        assertThat(membershipService.leave(owner.userId(), targetOwner.refrigeratorId())).isEqualTo(owner.refrigeratorId());
        assertThat(activeRefrigeratorId(owner.userId())).isEqualTo(owner.refrigeratorId());
        assertThat(invitationService.getValidCode(owner.userId(), owner.refrigeratorId())).isEqualTo(originalCode);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isEqualTo(2);
    }

    @Test
    void ownerCanMoveOnlyAfterTheOtherMemberLeaves() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        Fixture targetOwner = ownerFixture("Target");
        InvitationView originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId());
        membershipService.join(member.userId(), originalCode.code());
        InvitationView targetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId());

        assertError(ALREADY_SHARING, () -> membershipService.join(owner.userId(), targetCode.code()));
        assertThat(activeRefrigeratorId(owner.userId())).isEqualTo(owner.refrigeratorId());

        membershipService.leave(member.userId(), owner.refrigeratorId());

        assertThat(membershipService.join(owner.userId(), targetCode.code())).isEqualTo(targetOwner.refrigeratorId());
        assertThat(activeRefrigeratorId(member.userId())).isEqualTo(member.refrigeratorId());
    }

    @Test
    void codeCannotBeUsedWhileItsOwnerUsesAnotherRefrigerator() {
        Fixture owner = ownerFixture("Owner");
        Fixture targetOwner = ownerFixture("Target");
        Fixture joiningUser = ownerFixture("Joining");
        InvitationView originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId());
        InvitationView targetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId());
        membershipService.join(owner.userId(), targetCode.code());
        seedIngredient(joiningUser.refrigeratorId());

        assertError(EXPIRED_INVITE, () -> membershipService.join(joiningUser.userId(), originalCode.code()));

        assertThat(activeRefrigeratorId(joiningUser.userId())).isEqualTo(joiningUser.refrigeratorId());
        assertThat(refrigeratorMemberRepository.findLiveMembership(joiningUser.userId(), owner.refrigeratorId())).isEmpty();
        assertThat(refrigeratorService.getMembers(targetOwner.userId(), targetOwner.refrigeratorId()).memberNum()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isOne();
    }

    @Test
    void originalCodeWorksAgainWhenOwnerReturnsWithinItsOriginalValidity() {
        Fixture owner = ownerFixture("Owner");
        Fixture targetOwner = ownerFixture("Target");
        Fixture joiningUser = ownerFixture("Joining");
        InvitationView originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId());
        InvitationView targetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId());
        membershipService.join(owner.userId(), targetCode.code());
        membershipService.leave(owner.userId(), targetOwner.refrigeratorId());

        assertThat(membershipService.join(joiningUser.userId(), originalCode.code())).isEqualTo(owner.refrigeratorId());
        assertThat(invitationService.getValidCode(owner.userId(), owner.refrigeratorId())).isEqualTo(originalCode);
    }

    @Test
    void withdrawnMemberDoesNotPreventRemainingOwnerFromMoving() {
        Fixture owner = ownerFixture("Owner");
        Fixture member = ownerFixture("Member");
        Fixture targetOwner = ownerFixture("Target");
        InvitationView originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId());
        membershipService.join(member.userId(), originalCode.code());
        userWithdrawalService.withdraw(member.userId());
        InvitationView targetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId());

        assertThat(membershipService.join(owner.userId(), targetCode.code())).isEqualTo(targetOwner.refrigeratorId());
    }

    @Test
    void failedMovementKeepsTheOriginalCodeUsable() {
        Fixture owner = ownerFixture("Owner");
        Fixture targetOwner = ownerFixture("Target");
        Fixture joiningUser = ownerFixture("Joining");
        InvitationView originalCode = invitationService.issueCode(owner.userId(), owner.refrigeratorId());
        InvitationView oldTargetCode = invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId());
        invitationService.issueCode(targetOwner.userId(), targetOwner.refrigeratorId());

        assertError(EXPIRED_INVITE, () -> membershipService.join(owner.userId(), oldTargetCode.code()));

        assertThat(activeRefrigeratorId(owner.userId())).isEqualTo(owner.refrigeratorId());
        assertThat(membershipService.join(joiningUser.userId(), originalCode.code())).isEqualTo(owner.refrigeratorId());
    }

    private Long activeRefrigeratorId(Long userId) {
        return refrigeratorMemberRepository.findActiveRefrigeratorId(userId).orElseThrow();
    }

    private void assertError(RefrigeratorExceptionCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(expected));
    }

    private void seedIngredient(Long refrigeratorId) {
        jdbcTemplate.update("INSERT INTO ingredients "
                + "(refrigerator_id,name,category,storage_type,measure_type,quantity,weight_unit,expiration_date,registration_source,owner_id) "
                + "VALUES (?,'carrot','VEGETABLE','REFRIGERATED','COUNT',1,'NONE','2026-10-20','DIRECT',"
                + "(SELECT user_id FROM refrigerator_members WHERE refrigerator_id=? AND role='OWNER' LIMIT 1))", refrigeratorId, refrigeratorId);
    }
}
