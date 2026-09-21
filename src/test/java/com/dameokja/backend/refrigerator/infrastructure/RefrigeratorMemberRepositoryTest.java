package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMemberRole;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefrigeratorMemberRepositoryTest extends MySqlJpaTest {
    @Autowired
    UserRepository userRepository;
    @Autowired
    RefrigeratorRepository refrigeratorRepository;
    @Autowired
    RefrigeratorMemberRepository refrigeratorMemberRepository;

    private User user(String nickname) {
        return userRepository.save(new User(nickname, "default.png"));
    }

    private Refrigerator refrigerator(String name) {
        return refrigeratorRepository.save(new Refrigerator(name, "2026-09"));
    }

    @Test
    void loginIdsExcludeInactiveAndOtherUsersRefrigerators() {
        User first = user("로그인회원");
        Refrigerator active = refrigerator("활성");
        refrigeratorMemberRepository.save(RefrigeratorMember.owner(first, active));
        refrigeratorMemberRepository.save(new RefrigeratorMember(first, refrigerator("비활성")));
        refrigeratorMemberRepository.save(
                RefrigeratorMember.owner(user("다른회원"), refrigerator("타인")));
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findActiveRefrigeratorIdsByUserId(first.getId()))
                .containsExactly(active.getId());
    }

    @Test
    void loginIdsExcludeDeletedRefrigeratorEvenWhenMembershipIsActive() {
        User first = user("로그인회원");
        Refrigerator deleted = refrigerator("삭제됨");
        deleted.delete(java.time.LocalDateTime.now());
        refrigeratorMemberRepository.save(RefrigeratorMember.owner(first, deleted));
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findActiveRefrigeratorIdsByUserId(first.getId()))
                .isEmpty();
    }

    @Test
    void savesOwnerWithTheCorrectRelationships() {
        User user = user("소유자");
        Refrigerator refrigerator = refrigerator("개인냉장고");
        RefrigeratorMember savedMembership = refrigeratorMemberRepository.save(
                RefrigeratorMember.owner(user, refrigerator));
        flushAndClear();
        RefrigeratorMember foundMembership = refrigeratorMemberRepository
                .findById(savedMembership.getId())
                .orElseThrow();
        assertThat(foundMembership.getUser().getId()).isEqualTo(user.getId());
        assertThat(foundMembership.getRefrigerator().getId()).isEqualTo(refrigerator.getId());
        assertThat(foundMembership.getUser().getNickname()).isEqualTo("소유자");
        assertThat(foundMembership.getRefrigerator().getName()).isEqualTo("개인냉장고");
        assertThat(foundMembership.getRole()).isEqualTo(RefrigeratorMemberRole.OWNER);
        assertThat(foundMembership.getIsActive()).isTrue();
        assertThat(foundMembership.getCreatedAt()).isNotNull();
        assertThat(foundMembership.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsOnlyTheRequestedUsersActiveMembership() {
        User firstUser = user("첫회원");
        User secondUser = user("다른회원");
        refrigeratorMemberRepository.save(new RefrigeratorMember(firstUser, refrigerator("비활성")));
        RefrigeratorMember activeMembership = refrigeratorMemberRepository.save(
                RefrigeratorMember.owner(firstUser, refrigerator("활성")));
        refrigeratorMemberRepository.save(
                RefrigeratorMember.owner(secondUser, refrigerator("타인냉장고")));
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(firstUser.getId()))
                .get()
                .extracting(RefrigeratorMember::getId).isEqualTo(activeMembership.getId());
        assertThat(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(Long.MAX_VALUE))
                .isEmpty();
    }

    @Test
    void returnsEmptyForUserWithOnlyInactiveMemberships() {
        User user = user("비활성회원");
        refrigeratorMemberRepository.save(new RefrigeratorMember(user, refrigerator("냉장고")));
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(user.getId()))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "refrigerator"})
    void rejectsMissingRelationship(String invalidField) {
        User user = user("회원이름");
        Refrigerator refrigerator = refrigerator("냉장고");
        assertThatThrownBy(() -> {
            refrigeratorMemberRepository.save(new RefrigeratorMember(
                    invalidField.equals("user") ? null : user,
                    invalidField.equals("refrigerator") ? null : refrigerator));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "refrigerator"})
    void rejectsNonexistentForeignKey(String invalidField) {
        User user = user("회원이름");
        Refrigerator refrigerator = refrigerator("냉장고");
        User userReference = invalidField.equals("user")
                ? entityManager.getReference(User.class, Long.MAX_VALUE) : user;
        Refrigerator refrigeratorReference = invalidField.equals("refrigerator")
                ? entityManager.getReference(Refrigerator.class, Long.MAX_VALUE) : refrigerator;
        assertThatThrownBy(() -> {
            refrigeratorMemberRepository.save(new RefrigeratorMember(
                    userReference, refrigeratorReference));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateRefrigeratorAndUserPair() {
        User user = user("회원이름");
        Refrigerator refrigerator = refrigerator("냉장고");
        refrigeratorMemberRepository.save(new RefrigeratorMember(user, refrigerator));
        flushAndClear();
        assertThatThrownBy(() -> {
            refrigeratorMemberRepository.save(new RefrigeratorMember(user, refrigerator));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTwoActiveMembershipsForOneUser() {
        User user = user("회원이름");
        Refrigerator firstRefrigerator = refrigerator("첫냉장고");
        Refrigerator secondRefrigerator = refrigerator("둘째냉장고");
        refrigeratorMemberRepository.save(RefrigeratorMember.owner(user, firstRefrigerator));
        flushAndClear();
        assertThatThrownBy(() -> {
            refrigeratorMemberRepository.save(RefrigeratorMember.owner(user, secondRefrigerator));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsMultipleInactiveMembershipsAndUpdatesSelection() {
        User user = user("회원이름");
        RefrigeratorMember firstMembership = refrigeratorMemberRepository.save(
                new RefrigeratorMember(user, refrigerator("첫냉장고")));
        RefrigeratorMember secondMembership = refrigeratorMemberRepository.save(
                new RefrigeratorMember(user, refrigerator("둘째냉장고")));
        flushAndClear();
        refrigeratorMemberRepository.findById(firstMembership.getId())
                .orElseThrow().changeActiveStatus(true);
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(user.getId())).get()
                .extracting(RefrigeratorMember::getId).isEqualTo(firstMembership.getId());
        assertThat(refrigeratorMemberRepository.findById(secondMembership.getId())
                .orElseThrow().getIsActive()).isFalse();
        refrigeratorMemberRepository.findById(firstMembership.getId())
                .orElseThrow().changeActiveStatus(false);
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(user.getId()))
                .isEmpty();
    }

    @Test
    void deletesAllTargetMembershipsWithoutDeletingOtherMembershipsOrParents() {
        User firstUser = user("첫회원");
        User secondUser = user("둘째회원");
        Refrigerator targetRefrigerator = refrigerator("삭제대상");
        Refrigerator otherRefrigerator = refrigerator("보존대상");
        RefrigeratorMember ownerMembership = refrigeratorMemberRepository.save(
                RefrigeratorMember.owner(firstUser, targetRefrigerator));
        RefrigeratorMember memberMembership = refrigeratorMemberRepository.save(
                new RefrigeratorMember(secondUser, targetRefrigerator));
        RefrigeratorMember preservedMembership = refrigeratorMemberRepository.save(
                RefrigeratorMember.owner(secondUser, otherRefrigerator));
        flushAndClear();
        assertThat(refrigeratorMemberRepository
                .deleteAllByRefrigeratorId(targetRefrigerator.getId()))
                .isEqualTo(2);
        flushAndClear();
        assertThat(refrigeratorMemberRepository.findById(ownerMembership.getId())).isEmpty();
        assertThat(refrigeratorMemberRepository.findById(memberMembership.getId())).isEmpty();
        assertThat(refrigeratorMemberRepository.findById(preservedMembership.getId())).isPresent();
        assertThat(refrigeratorMemberRepository.findByUserIdAndIsActiveTrue(firstUser.getId()))
                .isEmpty();
        assertThat(userRepository.findById(firstUser.getId())).isPresent();
        assertThat(userRepository.findById(secondUser.getId())).isPresent();
        assertThat(refrigeratorRepository.findById(targetRefrigerator.getId())).isPresent();
        assertThat(refrigeratorRepository.findById(otherRefrigerator.getId())).isPresent();
    }
}
