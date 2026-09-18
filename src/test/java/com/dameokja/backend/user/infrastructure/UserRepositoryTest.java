package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTest extends MySqlJpaTest {
    @Autowired
    UserRepository userRepository;

    private User user(String nickname, String loginId) {
        return new User(nickname, "profiles/default.png", loginId,
                "x".repeat(60), LocalDateTime.of(2026, 9, 17, 0, 0));
    }

    @Test
    void savesAndFindsTheRequestedUser() {
        User firstUser = userRepository.save(user("첫회원", "first"));
        User secondUser = userRepository.save(user("다른회원", "second"));
        flushAndClear();
        User foundUser = userRepository.findById(firstUser.getId())
                .orElseThrow();
        assertThat(foundUser.getId()).isNotEqualTo(secondUser.getId());
        assertThat(foundUser.getNickname()).isEqualTo("첫회원");
        assertThat(foundUser.getProfileImageKey()).isEqualTo("profiles/default.png");
        assertThat(foundUser.getLoginId()).isEqualTo("first");
        assertThat(foundUser.getPasswordHash()).isEqualTo("x".repeat(60));
        assertThat(foundUser.getPasswordChangedAt()).isEqualTo(LocalDateTime.of(2026, 9, 17, 0, 0));
        assertThat(foundUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(foundUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(foundUser.getCookingCount()).isZero();
        assertThat(foundUser.getDeletedAt()).isNull();
        assertThat(foundUser.getCreatedAt()).isNotNull();
        assertThat(foundUser.getUpdatedAt()).isNotNull();
        assertThat(userRepository.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void checksNicknameAndLoginIdDuplicates() {
        userRepository.save(user("기존회원", "existing"));
        flushAndClear();
        assertThat(userRepository.existsByNickname("기존회원")).isTrue();
        assertThat(userRepository.existsByNickname("없는회원")).isFalse();
        assertThat(userRepository.existsByLoginId("existing")).isTrue();
        assertThat(userRepository.existsByLoginId("missing")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nickname", "loginId"})
    void rejectsDuplicates(String invalidField) {
        userRepository.save(user("기존회원", "existing"));
        flushAndClear();
        assertThatThrownBy(() -> {
            userRepository.save(user(invalidField.equals("nickname") ? "기존회원" : "다른회원",
                    invalidField.equals("loginId") ? "existing" : "different"));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nickname", "profile"})
    void rejectsMissingRequiredInput(String invalidField) {
        assertThatThrownBy(() -> {
            userRepository.save(new User(invalidField.equals("nickname") ? null : "회원이름",
                    invalidField.equals("profile") ? null : "default.png", null, null, null));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updatesProfileAndAuditingWithoutChangingOtherFields() {
        User savedUser = userRepository.save(user("수정전", "update"));
        flushAndClear();
        User foundUser = userRepository.findById(savedUser.getId())
                .orElseThrow();
        LocalDateTime createdAt = foundUser.getCreatedAt();
        LocalDateTime updatedAt = foundUser.getUpdatedAt();
        foundUser.updateProfile("수정후", "profiles/new.png");
        flushAndClear();
        User updatedUser = userRepository.findById(savedUser.getId())
                .orElseThrow();
        assertThat(updatedUser.getNickname()).isEqualTo("수정후");
        assertThat(updatedUser.getProfileImageKey()).isEqualTo("profiles/new.png");
        assertThat(updatedUser.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updatedUser.getUpdatedAt()).isAfter(updatedAt);
        assertThat(updatedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(updatedUser.getCookingCount()).isZero();
    }
}
