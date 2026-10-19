package com.dameokja.backend.refrigerator.application;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.EXPIRED_INVITE;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.FULL;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWNER_CANNOT_LEAVE;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWNER_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class RefrigeratorPolicyTest {
    private final RefrigeratorPolicy refrigeratorPolicy = new RefrigeratorPolicy();

    @Test
    void soloOwnerHasNoOtherMembers() {
        RefrigeratorMember current = owner(1L);
        assertThat(refrigeratorPolicy.hasOtherMembers(current, List.of(current))).isFalse();
        assertThat(refrigeratorPolicy.hasOtherMembers(current, List.of())).isFalse();
    }

    @Test
    void anotherActiveMemberCountsEvenWhenNotSelected() {
        RefrigeratorMember current = owner(1L);
        RefrigeratorMember other = new RefrigeratorMember(new User("Other", "default.png"), current.getRefrigerator());
        assertThat(other.isSelected()).isFalse();

        assertThat(refrigeratorPolicy.hasOtherMembers(current, List.of(current, other))).isTrue();
        assertThat(current.isSelected()).isTrue();
        assertThat(other.isSelected()).isFalse();
    }

    @Test
    void withdrawnMemberDoesNotPreventOwnerMovement() {
        RefrigeratorMember current = owner(1L);
        RefrigeratorMember withdrawn = new RefrigeratorMember(new User("Other", "default.png"), current.getRefrigerator());
        withdrawn.getUser().withdraw("withdrawn", LocalDateTime.of(2026, 10, 15, 12, 0));

        assertThat(refrigeratorPolicy.hasOtherMembers(current, List.of(current, withdrawn))).isFalse();
    }

    @Test
    void selectedOwnerAllowsInvitationParticipation() {
        RefrigeratorMember ownerMembership = owner(1L);

        assertThatCode(() -> refrigeratorPolicy.validateOwnerIsUsingRefrigerator(List.of(ownerMembership)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "withdrawn", "ended", "missing"})
    void rejectsInvitationWithoutAnActiveSelectedOwner(String unavailableState) {
        RefrigeratorMember ownerMembership = owner(1L);
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 15, 12, 0);
        if (unavailableState.equals("inactive")) {
            ownerMembership.deactivate();
        } else if (unavailableState.equals("withdrawn")) {
            ownerMembership.getUser().withdraw("withdrawn", endedAt);
        } else if (unavailableState.equals("ended")) {
            ownerMembership.end(endedAt);
        }
        List<RefrigeratorMember> liveMembers = unavailableState.equals("missing") ? List.of() : List.of(ownerMembership);

        assertError(EXPIRED_INVITE, () -> refrigeratorPolicy.validateOwnerIsUsingRefrigerator(liveMembers));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 149})
    void allowsJoiningUntilOneHundredFiftyMembers(int memberCount) {
        assertThatCode(() -> refrigeratorPolicy.validateCapacity(liveMembers(memberCount))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {150, 151})
    void countsOwnerAndRejectsFullOrOverCapacity(int memberCount) {
        List<RefrigeratorMember> liveMembers = liveMembers(memberCount);
        assertError(FULL, () -> refrigeratorPolicy.validateCapacity(liveMembers));
    }

    @Test
    void withdrawnUserDoesNotConsumeCapacity() {
        List<RefrigeratorMember> liveMembers = liveMembers(150);
        RefrigeratorMember withdrawn = liveMembers.getLast();
        withdrawn.getUser().withdraw("withdrawn", LocalDateTime.of(2026, 10, 13, 12, 0));
        assertThatCode(() -> refrigeratorPolicy.validateCapacity(liveMembers)).doesNotThrowAnyException();
    }

    @Test
    void selectsNextOwnerByEarliestJoinTimeRegardlessOfInputOrder() {
        LocalDateTime joinedAt = LocalDateTime.of(2026, 10, 13, 12, 0);
        RefrigeratorMember withdrawingOwner = owner(1L);
        RefrigeratorMember earlierMember = member(2L, 30L, joinedAt);
        RefrigeratorMember laterMember = member(3L, 20L, joinedAt.plusMinutes(1));

        assertThat(refrigeratorPolicy.selectNextOwner(List.of(laterMember, withdrawingOwner, earlierMember), 1L))
                .contains(earlierMember);
        assertThat(earlierMember.isOwner()).isFalse();
        assertThat(withdrawingOwner.isOwner()).isTrue();
    }

    @Test
    void selectsNextOwnerByMembershipIdWhenJoinTimesAreEqual() {
        LocalDateTime joinedAt = LocalDateTime.of(2026, 10, 13, 12, 0);
        RefrigeratorMember higherIdMember = member(2L, 30L, joinedAt);
        RefrigeratorMember lowerIdMember = member(3L, 20L, joinedAt);

        assertThat(refrigeratorPolicy.selectNextOwner(List.of(higherIdMember, lowerIdMember), 1L))
                .contains(lowerIdMember);
    }

    @Test
    void excludesWithdrawingAndWithdrawnUsersFromNextOwnerCandidates() {
        LocalDateTime joinedAt = LocalDateTime.of(2026, 10, 13, 12, 0);
        RefrigeratorMember withdrawingOwner = owner(1L);
        RefrigeratorMember withdrawnMember = member(2L, 20L, joinedAt);
        RefrigeratorMember activeMember = member(3L, 30L, joinedAt.plusMinutes(1));
        withdrawnMember.getUser().withdraw("withdrawn", joinedAt.plusMinutes(2));

        assertThat(refrigeratorPolicy.selectNextOwner(List.of(withdrawingOwner, withdrawnMember, activeMember), 1L))
                .contains(activeMember);
        assertThat(refrigeratorPolicy.selectNextOwner(List.of(withdrawingOwner, withdrawnMember), 1L)).isEmpty();
        assertThat(refrigeratorPolicy.selectNextOwner(List.of(), 1L)).isEmpty();
    }

    @Test
    void ownerCannotLeaveOrRemoveSelf() {
        RefrigeratorMember owner = owner(1L);
        assertError(OWNER_CANNOT_LEAVE, () -> owner.validateLeave());
        assertError(OWNER_ONLY, () -> owner.validateRemovalTarget(owner));
    }

    @Test
    void memberCanLeaveButCannotRemoveOthers() {
        RefrigeratorMember member = new RefrigeratorMember(new User("Member", "default.png"), new Refrigerator("shared", "2026-10"));
        assertThatCode(() -> member.validateLeave()).doesNotThrowAnyException();
        assertError(OWNER_ONLY, () -> member.validateOwnerForRemoval());
    }

    private List<RefrigeratorMember> liveMembers(int count) {
        RefrigeratorMember owner = owner(1L);
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> index == 1 ? owner
                        : new RefrigeratorMember(new User("Member" + index, "default.png"), owner.getRefrigerator()))
                .toList();
    }

    private RefrigeratorMember owner(Long refrigeratorId) {
        User user = new User("Owner", "default.png");
        ReflectionTestUtils.setField(user, "id", refrigeratorId);
        Refrigerator refrigerator = new Refrigerator("home", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", refrigeratorId);
        return RefrigeratorMember.owner(user, refrigerator);
    }

    private RefrigeratorMember member(Long userId, Long membershipId, LocalDateTime createdAt) {
        User user = new User("Member" + userId, "default.png");
        ReflectionTestUtils.setField(user, "id", userId);
        RefrigeratorMember refrigeratorMember = new RefrigeratorMember(user, new Refrigerator("shared", "2026-10"));
        ReflectionTestUtils.setField(refrigeratorMember, "id", membershipId);
        ReflectionTestUtils.setField(refrigeratorMember, "createdAt", createdAt);
        return refrigeratorMember;
    }

    private void assertError(ExceptionCode expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(expected));
    }
}
