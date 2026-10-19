package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.global.exception.GlobalExceptionCode.INTERNAL_SERVER_ERROR;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWNER_ONLY;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWNER_CANNOT_LEAVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class RefrigeratorMemberRulesTest {

    @Test
    void selectedMembershipCanAccessItsRefrigerator() {
        Refrigerator refrigerator = new Refrigerator("shared", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", 1L);
        RefrigeratorMember refrigeratorMember = RefrigeratorMember.owner(new User("Owner", "default.png"), refrigerator);

        assertThatCode(() -> refrigeratorMember.validateSelectedAccessTo(1L)).doesNotThrowAnyException();
        assertThat(refrigeratorMember.isSelected()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ended", "unselected", "differentRefrigerator"})
    void rejectsAccessWithoutALiveSelectedMembershipForTheTarget(String unavailableState) {
        Refrigerator refrigerator = new Refrigerator("shared", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", 1L);
        RefrigeratorMember refrigeratorMember = RefrigeratorMember.owner(new User("Owner", "default.png"), refrigerator);
        if (unavailableState.equals("ended")) {
            refrigeratorMember.end(LocalDateTime.of(2026, 10, 15, 12, 0));
        } else if (unavailableState.equals("unselected")) {
            refrigeratorMember.deactivate();
        }
        Long targetRefrigeratorId = unavailableState.equals("differentRefrigerator") ? 2L : 1L;

        assertError(ACCESS_DENIED, () -> refrigeratorMember.validateSelectedAccessTo(targetRefrigeratorId));
        assertThat(refrigeratorMember.isOwner()).isTrue();
    }

    @Test
    void leavingSharedRefrigeratorEnforcesOwnerRuleAtTheStateChange() {
        RefrigeratorMember ownerMembership = RefrigeratorMember.owner(
                new User("Owner", "default.png"), new Refrigerator("shared", "2026-10"));
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 14, 12, 0);
        assertError(OWNER_CANNOT_LEAVE, () -> ownerMembership.leaveSharedRefrigerator(endedAt));
        assertThat(ownerMembership.isSelected()).isTrue();
        assertThat(ownerMembership.getEndedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "refrigerator"})
    void rejectsMissingRelationshipAtCreation(String missingRelationship) {
        User user = new User("Member", "default.png");
        Refrigerator refrigerator = new Refrigerator("shared", "2026-10");
        assertError(INTERNAL_SERVER_ERROR, () -> new RefrigeratorMember(
                missingRelationship.equals("user") ? null : user,
                missingRelationship.equals("refrigerator") ? null : refrigerator));
    }

    @Test
    void endsOwnerForAccountWithdrawalButPreventsReactivationUntilRejoin() {
        RefrigeratorMember ownerMembership = RefrigeratorMember.owner(
                new User("Owner", "default.png"), new Refrigerator("shared", "2026-10"));
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 14, 12, 0);
        ownerMembership.end(endedAt);
        assertThat(ownerMembership.isLive()).isFalse();
        assertThat(ownerMembership.isSelected()).isFalse();
        assertThat(ownerMembership.isOwner()).isTrue();
        assertError(INTERNAL_SERVER_ERROR, ownerMembership::activate);

        ownerMembership.joinSharedRefrigerator();
        assertThat(ownerMembership.isSelected()).isTrue();
        assertThat(ownerMembership.isOwner()).isFalse();
        assertThat(ownerMembership.getEndedAt()).isNull();
    }

    @Test
    void requiresEndTimeWithoutPartiallyChangingTheMembership() {
        RefrigeratorMember ownerMembership = RefrigeratorMember.owner(
                new User("Owner", "default.png"), new Refrigerator("shared", "2026-10"));
        assertError(INTERNAL_SERVER_ERROR, () -> ownerMembership.end(null));
        assertThat(ownerMembership.isSelected()).isTrue();
        assertThat(ownerMembership.getEndedAt()).isNull();
    }

    @Test
    void ownerCanRemoveAnotherMemberButCannotRemoveAnotherOwner() {
        Refrigerator refrigerator = new Refrigerator("shared", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", 1L);
        RefrigeratorMember ownerMembership = RefrigeratorMember.owner(new User("Owner", "default.png"), refrigerator);
        RefrigeratorMember memberMembership = new RefrigeratorMember(new User("Member", "default.png"), refrigerator);
        ownerMembership.validateOwnerForRemoval();
        ownerMembership.validateRemovalTarget(memberMembership);

        RefrigeratorMember anotherOwner = RefrigeratorMember.owner(new User("Other", "default.png"), refrigerator);
        assertError(OWNER_ONLY, () -> ownerMembership.validateRemovalTarget(anotherOwner));
    }

    private void assertError(com.dameokja.backend.global.exception.ExceptionCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(expected));
    }
}
