package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class UserRepositoryTest extends MySqlJpaTest {
    @Autowired UserRepository users;

    private User user(String nickname, String loginId) {
        return new User(nickname, "profiles/default.png", loginId, "x".repeat(60), LocalDateTime.of(2026, 9, 17, 0, 0));
    }

    @Test void savesAndFindsTheRequestedUser() {
        User first = users.save(user("첫회원", "first"));
        User second = users.save(user("다른회원", "second"));
        flushAndClear();
        User found = users.findById(first.getId()).orElseThrow();
        assertThat(found.getId()).isNotEqualTo(second.getId());
        assertThat(found.getNickname()).isEqualTo("첫회원");
        assertThat(found.getProfileImageKey()).isEqualTo("profiles/default.png");
        assertThat(found.getLoginId()).isEqualTo("first");
        assertThat(found.getPasswordHash()).isEqualTo("x".repeat(60));
        assertThat(found.getPasswordChangedAt()).isEqualTo(LocalDateTime.of(2026, 9, 17, 0, 0));
        assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(found.getRole()).isEqualTo(UserRole.USER);
        assertThat(found.getCookingCount()).isZero();
        assertThat(found.getDeletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(users.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test void checksNicknameAndLoginIdDuplicates() {
        users.save(user("기존회원", "existing"));
        flushAndClear();
        assertThat(users.existsByNickname("기존회원")).isTrue();
        assertThat(users.existsByNickname("없는회원")).isFalse();
        assertThat(users.existsByLoginId("existing")).isTrue();
        assertThat(users.existsByLoginId("missing")).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"nickname", "loginId"})
    void rejectsDuplicates(String field) {
        users.save(user("기존회원", "existing"));
        flushAndClear();
        assertThatThrownBy(() -> {
            users.save(user(field.equals("nickname") ? "기존회원" : "다른회원",
                    field.equals("loginId") ? "existing" : "different"));
            entityManager.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"nickname", "profile"})
    void rejectsMissingRequiredInput(String field) {
        assertThatThrownBy(() -> {
            users.save(new User(field.equals("nickname") ? null : "회원이름",
                    field.equals("profile") ? null : "default.png", null, null, null));
            entityManager.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void updatesNicknameAndAuditingWithoutChangingOtherFields() {
        User saved = users.save(user("수정전", "update"));
        flushAndClear();
        User found = users.findById(saved.getId()).orElseThrow();
        LocalDateTime created = found.getCreatedAt();
        LocalDateTime updated = found.getUpdatedAt();
        found.updateNickname("수정후");
        flushAndClear();
        User result = users.findById(saved.getId()).orElseThrow();
        assertThat(result.getNickname()).isEqualTo("수정후");
        assertThat(result.getProfileImageKey()).isEqualTo("profiles/default.png");
        assertThat(result.getCreatedAt()).isEqualTo(created);
        assertThat(result.getUpdatedAt()).isAfter(updated);
        assertThat(result.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(result.getCookingCount()).isZero();
    }
}
