package com.dameokja.backend.auth.integration;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.infrastructure.RefreshSessionStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.UserWithdrawalService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserCredentials;
import com.dameokja.backend.user.domain.UserStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthWithdrawalIntegrationTest extends ServiceIntegrationTest {
    @Autowired AuthService authService;
    @Autowired JwtProvider jwtProvider;
    @Autowired PasswordEncoder passwords;
    @Autowired UserWithdrawalService withdrawals;
    @Autowired RefreshSessionStore refreshSessionStore;
    @Autowired PlatformTransactionManager transactions;

    private User account() {
        return userRepository.saveAndFlush(new User("User1", "profiles/test.png",
                new UserCredentials("login1", passwords.encode("password"),
                        LocalDateTime.of(2026, 9, 17, 12, 0))));
    }

    @Test
    void committedWithdrawalRevokesAllStoredSessionsImmediately() {
        var user = account();
        var first = authService.login("login1", "password");
        var second = authService.login("login1", "password");
        var renewed = authService.refresh(first.refreshToken());
        withdrawals.withdraw(user.getId());
        assertStoredRevoked(renewed);
        assertStoredRevoked(second);
    }

    @Test
    void databaseRollbackDoesNotRestoreAlreadyRevokedCacheEntries() {
        var user = account();
        var first = authService.login("login1", "password");
        var second = authService.login("login1", "password");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            withdrawals.withdraw(user.getId());
            status.setRollbackOnly();
        });
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        assertStoredRevoked(first);
        assertStoredRevoked(second);
    }

    @Test
    void concurrentLoginAndWithdrawalCannotLeaveAnActiveRefresh() throws Exception {
        var user = account();
        var outcomes = concurrently(List.of(() -> authService.login("login1", "password"), () -> {
            withdrawals.withdraw(user.getId());
            return "withdrawn";
        }));
        assertThat(outcomes.get(1)).isEqualTo("withdrawn");
        if (outcomes.get(0) instanceof TokenPair tokens) {
            assertStoredRevoked(tokens);
        } else {
            assertThat(outcomes.get(0)).isInstanceOf(CustomException.class);
        }
    }

    @Test
    void concurrentRefreshAndWithdrawalCannotResurrectTheSession() throws Exception {
        var user = account();
        var login = authService.login("login1", "password");
        var outcomes = concurrently(List.of(() -> authService.refresh(login.refreshToken()), () -> {
            withdrawals.withdraw(user.getId());
            return "withdrawn";
        }));
        assertThat(outcomes.get(1)).isEqualTo("withdrawn");
        if (outcomes.get(0) instanceof TokenPair tokens) {
            assertStoredRevoked(tokens);
        } else {
            assertThat(outcomes.get(0)).isInstanceOf(CustomException.class);
            assertStoredRevoked(login);
        }
    }

    @Test
    void usedReplayRevocationSurvivesRefreshTransactionRollback() {
        account();
        var first = authService.login("login1", "password");
        var second = authService.login("login1", "password");
        var renewed = authService.refresh(first.refreshToken());
        assertThatThrownBy(() -> authService.refresh(first.refreshToken()))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error
                        .getExceptionCode()).isEqualTo(AuthExceptionCode.REFRESH_INVALID));
        assertStoredRevoked(renewed);
        assertStoredRevoked(second);
    }

    private void assertStoredRevoked(TokenPair tokens) {
        var previous = jwtProvider.parseRefreshTokenPayload(tokens.refreshToken());
        var next = jwtProvider.parseRefreshTokenPayload(
                jwtProvider.createRefreshToken(previous.userId(), previous.sid()));
        assertThatThrownBy(() -> refreshSessionStore.rotate(previous, next))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error
                        .getExceptionCode())
                        .isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_REVOKED));
    }
}
