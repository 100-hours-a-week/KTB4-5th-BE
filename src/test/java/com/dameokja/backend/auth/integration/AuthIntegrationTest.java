package com.dameokja.backend.auth.integration;

import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserCredentials;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthIntegrationTest extends ServiceIntegrationTest {
    @Autowired AuthService authService;
    @Autowired JwtProvider jwtProvider;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void authenticatesPersistedCredentialsAndRejectsRefreshAfterWithdrawal() {
        User user = userRepository.saveAndFlush(new User("User1", "profiles/test.png",
                new UserCredentials("login1", passwordEncoder.encode("password"),
                        LocalDateTime.of(2026, 9, 20, 0, 0))));
        TokenPair login = authService.login("login1", "password");
        assertThat(jwtProvider.parseAccessTokenPayload(login.accessToken()).userId())
                .isEqualTo(user.getId());
        TokenPair refreshed = authService.refresh(login.refreshToken());
        user.withdraw("withdrawn1", LocalDateTime.of(2026, 9, 20, 1, 0));
        userRepository.saveAndFlush(user);
        assertThatThrownBy(() -> authService.refresh(refreshed.refreshToken()))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getExceptionCode())
                .isEqualTo(SecurityExceptionCode.USER_NOT_ACTIVE);
    }
}
