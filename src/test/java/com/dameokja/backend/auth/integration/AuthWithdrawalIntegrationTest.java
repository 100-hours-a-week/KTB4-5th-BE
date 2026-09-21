package com.dameokja.backend.auth.integration;

import com.dameokja.backend.global.security.RefreshTokenPayload;
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
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(AuthWithdrawalIntegrationTest.WithdrawalRollbackService.class)
class AuthWithdrawalIntegrationTest extends ServiceIntegrationTest {
    @Autowired AuthService authService;
    @Autowired JwtProvider jwtProvider;
    @Autowired PasswordEncoder passwords;
    @Autowired UserWithdrawalService withdrawals;
    @Autowired RefreshSessionStore refreshSessionStore;
    @Autowired WithdrawalRollbackService withdrawalRollbackService;

    private User account() {
        return userRepository.saveAndFlush(new User("User1", "profiles/test.png",
                new UserCredentials("login1", passwords.encode("password"),
                        LocalDateTime.of(2026, 9, 17, 12, 0))));
    }

    @Test
    void committedWithdrawalRevokesAllStoredSessionsImmediately() {
        User user = account();
        TokenPair first = authService.login("login1", "password");
        TokenPair second = authService.login("login1", "password");
        TokenPair renewed = authService.refresh(first.refreshToken());
        withdrawals.withdraw(user.getId());
        assertStoredRevoked(renewed);
        assertStoredRevoked(second);
    }

    @Test
    void databaseRollbackDoesNotRestoreAlreadyRevokedCacheEntries() {
        User user = account();
        TokenPair first = authService.login("login1", "password");
        TokenPair second = authService.login("login1", "password");
        assertThatThrownBy(() -> withdrawalRollbackService.withdrawAndFail(user.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("탈퇴 후 DB 롤백 검증");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
        assertStoredRevoked(first);
        assertStoredRevoked(second);
    }

    @Test
    void concurrentLoginAndWithdrawalCannotLeaveAnActiveRefresh() throws Exception {
        User user = account();
        List<Object> outcomes =
                concurrently(List.of(() -> authService.login("login1", "password"), () -> {
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
        User user = account();
        TokenPair login = authService.login("login1", "password");
        List<Object> outcomes =
                concurrently(List.of(() -> authService.refresh(login.refreshToken()), () -> {
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
        TokenPair first = authService.login("login1", "password");
        TokenPair second = authService.login("login1", "password");
        TokenPair renewed = authService.refresh(first.refreshToken());
        assertThatThrownBy(() -> authService.refresh(first.refreshToken()))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error
                        .getExceptionCode()).isEqualTo(AuthExceptionCode.REFRESH_INVALID));
        assertStoredRevoked(renewed);
        assertStoredRevoked(second);
    }

    private void assertStoredRevoked(TokenPair tokens) {
        RefreshTokenPayload previous = jwtProvider.parseRefreshTokenPayload(tokens.refreshToken());
        RefreshTokenPayload next = jwtProvider.parseRefreshTokenPayload(
                jwtProvider.createRefreshToken(previous.userId(), previous.sid()));
        assertThatThrownBy(() -> refreshSessionStore.rotate(previous, next))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error
                        .getExceptionCode())
                        .isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_REVOKED));
    }

    public static class WithdrawalRollbackService {
        private final UserWithdrawalService userWithdrawalService;

        public WithdrawalRollbackService(UserWithdrawalService userWithdrawalService) {
            this.userWithdrawalService = userWithdrawalService;
        }

        @Transactional
        public void withdrawAndFail(Long userId) {
            userWithdrawalService.withdraw(userId);
            throw new IllegalStateException("탈퇴 후 DB 롤백 검증");
        }
    }

}
