package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.RefrigeratorLifecycleService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserCredentials;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
abstract class UserWithdrawalUnitTest {
    @Mock
    protected UserRepository userRepository;
    @Mock
    protected RefrigeratorLifecycleService refrigeratorLifecycleService;
    protected UserWithdrawalService userWithdrawalService;

    @BeforeEach
    void setUp() {
        useTime("2026-09-17T03:00:00Z");
    }

    protected void useTime(String instantText) {
        Clock clock = Clock.fixed(Instant.parse(instantText), ZoneId.of("Asia/Seoul"));
        userWithdrawalService = new UserWithdrawalService(
                userRepository, refrigeratorLifecycleService, clock);
    }

    protected User existingUser() {
        User user = new User("User1", "original.png",
                new UserCredentials("login1", "hash", null));
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    protected void assertUserError(UserExceptionCode expectedExceptionCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedExceptionCode));
    }
}
