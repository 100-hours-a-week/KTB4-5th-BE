package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserRepository;
import com.dameokja.backend.user.domain.UserRole;
import com.dameokja.backend.user.domain.UserStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class UserWithdrawalRepositoryTest extends MySqlJpaTest {
    @Autowired
    private UserRepository userRepository;

    @Test
    void persistsWithdrawalAndClearsLoginCredentials() {
        LocalDateTime registeredAt = LocalDateTime.of(2026, 9, 17, 12, 0);
        User user = userRepository.save(new User("회원이름", "profiles/custom.png",
                "login1", "x".repeat(60), registeredAt));
        flushAndClear();
        User savedUser = userRepository.findById(user.getId()).orElseThrow();
        LocalDateTime createdAt = savedUser.getCreatedAt();
        LocalDateTime withdrawnAt = registeredAt.plusDays(1);
        savedUser.withdraw("탈퇴회원123", withdrawnAt);
        flushAndClear();
        User withdrawnUser = userRepository.findById(user.getId()).orElseThrow();
        assertThat(withdrawnUser.getNickname()).isEqualTo("탈퇴회원123");
        assertThat(withdrawnUser.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(withdrawnUser.getDeletedAt()).isEqualTo(withdrawnAt);
        assertThat(withdrawnUser.getLoginId()).isNull();
        assertThat(withdrawnUser.getPasswordHash()).isNull();
        assertThat(withdrawnUser.getPasswordChangedAt()).isNull();
        assertThat(withdrawnUser.getProfileImageKey()).isEqualTo("profiles/custom.png");
        assertThat(withdrawnUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(withdrawnUser.getCookingCount()).isZero();
        assertThat(withdrawnUser.getCreatedAt()).isEqualTo(createdAt);
    }
}
