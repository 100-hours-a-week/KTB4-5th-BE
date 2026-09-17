package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRepository;
import com.dameokja.backend.refrigerator.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

class RefrigeratorMemberRepositoryTest extends MySqlJpaTest {
    @Autowired UserRepository users;
    @Autowired RefrigeratorRepository refrigerators;
    @Autowired RefrigeratorMemberRepository members;

    private User user(String nickname) {
        return users.save(new User(nickname, "default.png", null, null, null));
    }

    private Refrigerator refrigerator(String name) {
        return refrigerators.save(new Refrigerator(name, "2026-09"));
    }

    @Test void savesOwnerWithTheCorrectRelationships() {
        User user = user("소유자");
        Refrigerator refrigerator = refrigerator("개인냉장고");
        RefrigeratorMember saved = members.save(RefrigeratorMember.owner(user, refrigerator));
        flushAndClear();
        RefrigeratorMember found = members.findById(saved.getId()).orElseThrow();
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getRefrigerator().getId()).isEqualTo(refrigerator.getId());
        assertThat(found.getUser().getNickname()).isEqualTo("소유자");
        assertThat(found.getRefrigerator().getName()).isEqualTo("개인냉장고");
        assertThat(found.getRole()).isEqualTo(RefrigeratorMemberRole.OWNER);
        assertThat(found.getIsActive()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test void appliesDatabaseDefaultsToUnspecifiedMembershipFields() {
        RefrigeratorMember saved = members.save(new RefrigeratorMember(user("일반회원"), refrigerator("냉장고")));
        assertThat(saved.getRole()).isEqualTo(RefrigeratorMemberRole.MEMBER);
        assertThat(saved.getIsActive()).isFalse();
        flushAndClear();
        RefrigeratorMember found = members.findById(saved.getId()).orElseThrow();
        assertThat(found.getRole()).isEqualTo(RefrigeratorMemberRole.MEMBER);
        assertThat(found.getIsActive()).isFalse();
    }

    @Test void findsOnlyTheRequestedUsersActiveMembership() {
        User first = user("첫회원");
        User second = user("다른회원");
        members.save(new RefrigeratorMember(first, refrigerator("비활성")));
        RefrigeratorMember active = members.save(RefrigeratorMember.owner(first, refrigerator("활성")));
        members.save(RefrigeratorMember.owner(second, refrigerator("타인냉장고")));
        flushAndClear();
        assertThat(members.findActiveByUserId(first.getId())).get()
                .extracting(RefrigeratorMember::getId).isEqualTo(active.getId());
        assertThat(members.findActiveByUserId(Long.MAX_VALUE)).isEmpty();
    }

    @Test void returnsEmptyForUserWithOnlyInactiveMemberships() {
        User user = user("비활성회원");
        members.save(new RefrigeratorMember(user, refrigerator("냉장고")));
        flushAndClear();
        assertThat(members.findActiveByUserId(user.getId())).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"user", "refrigerator"})
    void rejectsMissingRelationship(String field) {
        User user = user("회원이름");
        Refrigerator refrigerator = refrigerator("냉장고");
        assertThatThrownBy(() -> {
            members.save(new RefrigeratorMember(field.equals("user") ? null : user,
                    field.equals("refrigerator") ? null : refrigerator));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"user", "refrigerator"})
    void rejectsNonexistentForeignKey(String field) {
        User user = user("회원이름");
        Refrigerator refrigerator = refrigerator("냉장고");
        User userReference = field.equals("user") ? entityManager.getReference(User.class, Long.MAX_VALUE) : user;
        Refrigerator refrigeratorReference = field.equals("refrigerator")
                ? entityManager.getReference(Refrigerator.class, Long.MAX_VALUE) : refrigerator;
        assertThatThrownBy(() -> {
            members.save(new RefrigeratorMember(userReference, refrigeratorReference));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void rejectsDuplicateRefrigeratorAndUserPair() {
        User user = user("회원이름");
        Refrigerator refrigerator = refrigerator("냉장고");
        members.save(new RefrigeratorMember(user, refrigerator));
        flushAndClear();
        assertThatThrownBy(() -> {
            members.save(new RefrigeratorMember(user, refrigerator));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void rejectsTwoActiveMembershipsForOneUser() {
        User user = user("회원이름");
        Refrigerator first = refrigerator("첫냉장고");
        Refrigerator second = refrigerator("둘째냉장고");
        members.save(RefrigeratorMember.owner(user, first));
        flushAndClear();
        assertThatThrownBy(() -> {
            members.save(RefrigeratorMember.owner(user, second));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void allowsMultipleInactiveMembershipsAndUpdatesSelection() {
        User user = user("회원이름");
        RefrigeratorMember first = members.save(new RefrigeratorMember(user, refrigerator("첫냉장고")));
        RefrigeratorMember second = members.save(new RefrigeratorMember(user, refrigerator("둘째냉장고")));
        flushAndClear();
        members.findById(first.getId()).orElseThrow().changeActiveStatus(true);
        flushAndClear();
        assertThat(members.findActiveByUserId(user.getId())).get()
                .extracting(RefrigeratorMember::getId).isEqualTo(first.getId());
        assertThat(members.findById(second.getId()).orElseThrow().getIsActive()).isFalse();
        members.findById(first.getId()).orElseThrow().changeActiveStatus(false);
        flushAndClear();
        assertThat(members.findActiveByUserId(user.getId())).isEmpty();
    }

    @Test void deletesAllTargetMembershipsWithoutDeletingOtherMembershipsOrParents() {
        User first = user("첫회원");
        User second = user("둘째회원");
        Refrigerator target = refrigerator("삭제대상");
        Refrigerator other = refrigerator("보존대상");
        RefrigeratorMember a = members.save(RefrigeratorMember.owner(first, target));
        RefrigeratorMember b = members.save(new RefrigeratorMember(second, target));
        RefrigeratorMember kept = members.save(RefrigeratorMember.owner(second, other));
        flushAndClear();
        assertThat(members.deleteAllByRefrigeratorId(target.getId())).isEqualTo(2);
        flushAndClear();
        assertThat(members.findById(a.getId())).isEmpty();
        assertThat(members.findById(b.getId())).isEmpty();
        assertThat(members.findById(kept.getId())).isPresent();
        assertThat(members.findActiveByUserId(first.getId())).isEmpty();
        assertThat(users.findById(first.getId())).isPresent();
        assertThat(users.findById(second.getId())).isPresent();
        assertThat(refrigerators.findById(target.getId())).isPresent();
        assertThat(refrigerators.findById(other.getId())).isPresent();
    }
}
