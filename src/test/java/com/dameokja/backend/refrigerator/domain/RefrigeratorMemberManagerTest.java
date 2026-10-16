package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ACCESS_DENIED;
import static com.dameokja.backend.global.exception.GlobalExceptionCode.INTERNAL_SERVER_ERROR;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.ALREADY_SHARING;
import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.OWNER_CANNOT_LEAVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RefrigeratorMemberManagerTest {
    private final User user = new User("Member", "default.png");

    @Test
    void restoresRetainedPersonalMembershipAndExcludesDeletedAndEndedRelations() {
        RefrigeratorMember deletedPersonal = RefrigeratorMember.owner(user, refrigerator(1L));
        deletedPersonal.deactivate();
        deletedPersonal.getRefrigerator().delete(LocalDateTime.of(2026, 10, 13, 12, 0));
        RefrigeratorMember endedPersonal = RefrigeratorMember.owner(user, refrigerator(2L));
        endedPersonal.end(LocalDateTime.of(2026, 10, 13, 12, 0));
        RefrigeratorMember personal = RefrigeratorMember.owner(user, refrigerator(3L));
        personal.deactivate();
        RefrigeratorMember shared = new RefrigeratorMember(user, refrigerator(4L));
        shared.joinSharedRefrigerator();
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(deletedPersonal, endedPersonal, personal, shared));
        assertThat(refrigeratorMemberManager.getSelectedMembership()).isSameAs(shared);
        assertThat(refrigeratorMemberManager.preparePersonalReturn(shared)).isEqualTo(personal.getRefrigerator().getId());
    }

    @Test
    void rejectsRestoreWhenPersonalMembershipIsMissing() {
        RefrigeratorMember shared = new RefrigeratorMember(user, refrigerator(1L));
        shared.joinSharedRefrigerator();
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(shared));
        assertThatThrownBy(() -> refrigeratorMemberManager.preparePersonalReturn(shared)).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(ACCESS_DENIED));
    }

    @Test
    void rejoinReusesExistingRelationshipAndRestoresLiveSelectedState() {
        Refrigerator refrigerator = refrigerator(1L);
        RefrigeratorMember previous = new RefrigeratorMember(user, refrigerator);
        previous.end(LocalDateTime.of(2026, 10, 13, 12, 0));
        RefrigeratorMember personalMembership = RefrigeratorMember.owner(user, refrigerator(2L));
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(personalMembership, previous));
        assertThat(refrigeratorMemberManager.findLiveMembership(1L)).isEmpty();
        refrigeratorMemberManager.prepareJoin(user, refrigerator, false);
        assertThat(previous.getEndedAt()).isNotNull();
        assertThat(personalMembership.isSelected()).isTrue();
        refrigeratorMemberManager.deactivateForJoin(1L);
        RefrigeratorMember joining = refrigeratorMemberManager.joinSharedRefrigerator(1L);
        assertThat(joining).isSameAs(previous);
        assertThat(joining.isSelected()).isTrue();
        assertThat(joining.isOwner()).isFalse();
        assertThat(refrigeratorMemberManager.findLiveMembership(1L)).contains(previous);
    }

    @Test
    void createsUnselectedRelationshipWhenJoiningForFirstTime() {
        Refrigerator refrigerator = refrigerator(1L);
        RefrigeratorMember personalMembership = RefrigeratorMember.owner(user, refrigerator(2L));
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(personalMembership));
        refrigeratorMemberManager.prepareJoin(user, refrigerator, false);
        RefrigeratorMember joining = refrigeratorMemberManager.findLiveMembership(1L).orElseThrow();
        assertThat(joining.getUser()).isSameAs(user);
        assertThat(joining.getRefrigerator()).isSameAs(refrigerator);
        assertThat(joining.isSelected()).isFalse();
    }

    @Test
    void preparesJoinAndKeepsTheTransitionInsideTheSameUserCollection() {
        RefrigeratorMember personalMembership = RefrigeratorMember.owner(user, refrigerator(1L));
        List<RefrigeratorMember> originalMembers = new ArrayList<>(List.of(personalMembership));
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(originalMembers);
        refrigeratorMemberManager.prepareJoin(user, refrigerator(2L), false);
        assertThat(originalMembers).containsExactly(personalMembership);
        assertThat(refrigeratorMemberManager.findLiveMembership(2L).orElseThrow().isSelected()).isFalse();

        refrigeratorMemberManager.deactivateForJoin(2L);
        RefrigeratorMember joiningMembership = refrigeratorMemberManager.joinSharedRefrigerator(2L);

        assertThat(refrigeratorMemberManager.getSelectedMembership()).isSameAs(joiningMembership);
        assertThat(personalMembership.isLive()).isTrue();
        assertThat(personalMembership.isSelected()).isFalse();
    }

    @Test
    void rejectsRelationsAndJoiningRequestsFromAnotherUser() {
        User anotherUser = new User("Another", "default.png");
        RefrigeratorMember personalMembership = RefrigeratorMember.owner(user, refrigerator(1L));
        RefrigeratorMember foreignMembership = RefrigeratorMember.owner(anotherUser, refrigerator(2L));
        assertInternalError(() -> new RefrigeratorMemberManager(List.of(personalMembership, foreignMembership)));

        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(personalMembership));
        assertInternalError(() -> refrigeratorMemberManager.prepareJoin(anotherUser, refrigerator(3L), false));
        assertInternalError(() -> refrigeratorMemberManager.deactivateForJoin(2L));
        assertThat(personalMembership.isSelected()).isTrue();
    }

    @Test
    void rejectsTwoSelectedRelationsInsteadOfChoosingOneSilently() {
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(
                RefrigeratorMember.owner(user, refrigerator(1L)),
                RefrigeratorMember.owner(user, refrigerator(2L))));
        assertInternalError(refrigeratorMemberManager::getSelectedMembership);
    }

    @Test
    void rejectsAnUnpreparedTargetWithoutDeactivatingTheCurrentMembership() {
        RefrigeratorMember currentMembership = RefrigeratorMember.owner(user, refrigerator(1L));
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(currentMembership));
        assertInternalError(() -> refrigeratorMemberManager.deactivateForJoin(2L));
        assertThat(currentMembership.isSelected()).isTrue();
    }

    @Test
    void rejectsUnknownActivationTargetWithoutCreatingARelationshipOrChangingSelection() {
        RefrigeratorMember currentMembership = RefrigeratorMember.owner(user, refrigerator(1L));
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(currentMembership));

        assertInternalError(() -> refrigeratorMemberManager.joinSharedRefrigerator(2L));

        assertThat(refrigeratorMemberManager.findLiveMembership(2L)).isEmpty();
        assertThat(currentMembership.isSelected()).isTrue();
    }

    @Test
    void rejectsDeactivationForTheCurrentRefrigeratorWithoutChangingSelection() {
        RefrigeratorMember currentMembership = RefrigeratorMember.owner(user, refrigerator(1L));
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(currentMembership));

        assertInternalError(() -> refrigeratorMemberManager.deactivateForJoin(1L));

        assertThat(currentMembership.isSelected()).isTrue();
    }

    @Test
    void rejectsJoiningFromSharedStateBeforeChangingRelations() {
        RefrigeratorMember sharingMembership = new RefrigeratorMember(user, refrigerator(1L));
        sharingMembership.joinSharedRefrigerator();
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(sharingMembership));
        assertThatThrownBy(() -> refrigeratorMemberManager.prepareJoin(user, refrigerator(2L), false))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(ALREADY_SHARING));
        assertThat(refrigeratorMemberManager.getSelectedMembership()).isSameAs(sharingMembership);
    }

    @Test
    void rejectsOwnerReturnBeforeLookingForAMissingPersonalRelation() {
        RefrigeratorMember ownerMembership = RefrigeratorMember.owner(user, refrigerator(1L));
        RefrigeratorMemberManager refrigeratorMemberManager = new RefrigeratorMemberManager(List.of(ownerMembership));
        assertThatThrownBy(() -> refrigeratorMemberManager.preparePersonalReturn(ownerMembership))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode()).isEqualTo(OWNER_CANNOT_LEAVE));
    }

    @Test
    void personalReturnPreparesWithoutMutationThenEndsSharedBeforeActivatingPersonal() {
        RefrigeratorMember personal = RefrigeratorMember.owner(user, refrigerator(1L));
        personal.deactivate();
        RefrigeratorMember shared = new RefrigeratorMember(user, refrigerator(2L));
        shared.joinSharedRefrigerator();
        RefrigeratorMemberManager manager = new RefrigeratorMemberManager(List.of(personal, shared));
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 15, 12, 0);

        Long personalId = manager.preparePersonalReturn(shared);
        assertThat(personalId).isEqualTo(1L);
        assertThat(shared.isSelected()).isTrue();
        assertThat(personal.isSelected()).isFalse();

        manager.endSharedMembership(2L, endedAt);
        assertThat(shared.getEndedAt()).isEqualTo(endedAt);
        assertThat(shared.isSelected()).isFalse();
        assertThat(personal.isSelected()).isFalse();
        manager.activatePersonalMembership(personalId);
        assertThat(manager.getSelectedMembership()).isSameAs(personal);
    }

    @Test
    void personalReturnRejectsAnotherUsersRelationshipWithoutChangingSelection() {
        RefrigeratorMember personal = RefrigeratorMember.owner(user, refrigerator(1L));
        RefrigeratorMember foreign = new RefrigeratorMember(new User("Other", "default.png"), refrigerator(2L));
        foreign.joinSharedRefrigerator();
        RefrigeratorMemberManager manager = new RefrigeratorMemberManager(List.of(personal));

        assertInternalError(() -> manager.preparePersonalReturn(foreign));

        assertThat(personal.isSelected()).isTrue();
        assertThat(foreign.isSelected()).isTrue();
    }

    private void assertInternalError(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getExceptionCode()).isEqualTo(INTERNAL_SERVER_ERROR));
    }

    private Refrigerator refrigerator(Long id) {
        Refrigerator refrigerator = new Refrigerator("home", "2026-10");
        ReflectionTestUtils.setField(refrigerator, "id", id);
        return refrigerator;
    }
}
