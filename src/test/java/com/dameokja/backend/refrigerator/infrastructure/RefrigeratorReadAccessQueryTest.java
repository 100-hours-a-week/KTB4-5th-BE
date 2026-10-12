package com.dameokja.backend.refrigerator.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorMember;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserStatus;
import java.time.LocalDateTime;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class RefrigeratorReadAccessQueryTest extends MySqlJpaTest {
    @Autowired RefrigeratorRepository refrigerators;

    @ParameterizedTest
    @ValueSource(strings = {"owner", "member", "inactive", "none", "differentUser", "differentFridge"})
    void distinguishesActiveMembershipAndLoadsFridgeInOneStatement(String state) {
        User user = new User("조회회원", "p");
        entityManager.persist(user);
        Refrigerator fridge = refrigerators.save(new Refrigerator("조회냉장고", "2026-10"));
        Long membershipId = membership(state, user, fridge);
        flushAndClear();
        Statistics statistics = statistics();
        statistics.clear();

        RefrigeratorReadAccess access = refrigerators.findReadAccess(user.getId(), fridge.getId()).orElseThrow();

        assertThat(access.activeMembershipId()).isEqualTo(membershipId);
        assertThat(access.refrigerator().getName()).isEqualTo("조회냉장고");
        assertThat(access.refrigerator().getCapacity()).isEqualTo((short) 100);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void retainsDeletedFridgeWithoutMembershipAndDistinguishesMissingFridge() {
        User user = new User("삭제조회", "p");
        entityManager.persist(user);
        Refrigerator fridge = refrigerators.save(new Refrigerator("삭제냉장고", "2026-10"));
        LocalDateTime deletedAt = LocalDateTime.of(2026, 10, 3, 8, 0);
        fridge.delete(deletedAt);
        flushAndClear();
        RefrigeratorReadAccess access = refrigerators.findReadAccess(user.getId(), fridge.getId()).orElseThrow();
        assertThat(access.refrigerator().getDeletedAt()).isEqualTo(deletedAt);
        assertThat(access.activeMembershipId()).isNull();
        assertThat(refrigerators.findReadAccess(user.getId(), Long.MAX_VALUE).orElseThrow().refrigerator()).isNull();
        assertThat(refrigerators.findReadAccess(Long.MAX_VALUE, fridge.getId())).isEmpty();
    }

    @Test
    void returnsInactiveUserStatusEvenWhenFridgeIsMissingOrDeleted() {
        User user = new User("탈퇴조회", "p");
        entityManager.persist(user);
        user.withdraw("탈퇴회원", LocalDateTime.of(2026, 10, 3, 8, 0));
        Refrigerator fridge = refrigerators.save(new Refrigerator("삭제조회", "2026-10"));
        fridge.delete(LocalDateTime.of(2026, 10, 3, 8, 0));
        flushAndClear();
        assertThat(refrigerators.findReadAccess(user.getId(), fridge.getId()).orElseThrow().userStatus())
                .isEqualTo(UserStatus.WITHDRAWN);
        assertThat(refrigerators.findReadAccess(user.getId(), Long.MAX_VALUE).orElseThrow().userStatus())
                .isEqualTo(UserStatus.WITHDRAWN);
    }

    private Long membership(String state, User user, Refrigerator fridge) {
        if (state.equals("none")) {
            return null;
        }
        if (state.equals("differentUser")) {
            user = new User("다른회원", "p");
            entityManager.persist(user);
        }
        if (state.equals("differentFridge")) {
            fridge = refrigerators.save(new Refrigerator("다른냉장고", "2026-10"));
        }
        RefrigeratorMember member = state.equals("owner")
                ? RefrigeratorMember.owner(user, fridge) : new RefrigeratorMember(user, fridge);
        member.changeActiveStatus(!state.equals("inactive"));
        entityManager.persist(member);
        return state.equals("owner") || state.equals("member") ? member.getId() : null;
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }
}
