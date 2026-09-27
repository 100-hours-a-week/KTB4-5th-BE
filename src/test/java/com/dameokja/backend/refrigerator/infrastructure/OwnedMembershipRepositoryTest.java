package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class OwnedMembershipRepositoryTest extends MySqlJpaTest {
    @Autowired
    private RefrigeratorMemberRepository refrigeratorMemberRepository;

    @Test
    void findsActiveAndInactiveOwnersWithoutOtherUsersOrRoles() {
        User owner = user("소유자");
        User otherUser = user("다른회원");
        RefrigeratorMember activeOwner = ownership(owner, true);
        RefrigeratorMember inactiveOwner = ownership(owner, false);
        ownership(otherUser, true);
        entityManager.persist(new RefrigeratorMember(owner, refrigerator()));
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findByUserIdAndRole(
                owner.getId(), RefrigeratorMemberRole.OWNER))
                .extracting(RefrigeratorMember::getId)
                .containsExactlyInAnyOrder(activeOwner.getId(), inactiveOwner.getId());
    }

    @Test
    void returnsEmptyWhenUserOwnsNoRefrigerator() {
        User member = user("참여회원");
        entityManager.persist(new RefrigeratorMember(member, refrigerator()));
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findByUserIdAndRole(
                member.getId(), RefrigeratorMemberRole.OWNER)).isEmpty();
    }

    private User user(String nickname) {
        User user = new User(nickname, "default.png");
        entityManager.persist(user);
        return user;
    }

    private Refrigerator refrigerator() {
        Refrigerator refrigerator = new Refrigerator("냉장고", "2026-09");
        entityManager.persist(refrigerator);
        return refrigerator;
    }

    private RefrigeratorMember ownership(User user, boolean active) {
        RefrigeratorMember membership = RefrigeratorMember.owner(user, refrigerator());
        membership.changeActiveStatus(active);
        entityManager.persist(membership);
        return membership;
    }
}
