package com.dameokja.backend.auth.application;

import com.dameokja.backend.auth.infrastructure.CaffeineRefreshSessionStore;
import com.dameokja.backend.auth.infrastructure.RefreshSessionStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.user.application.AuthenticatedUser;
import com.dameokja.backend.user.application.UserAuthenticationService;
import com.dameokja.backend.user.domain.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    private final JwtProvider jwtProvider = new JwtProvider(
            "dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=", Duration.ofMinutes(15),
            Duration.ofDays(2), clock);
    private final UserAuthenticationService userAuthenticationService =
            mock(UserAuthenticationService.class);
    private final RefreshSessionStore refreshSessionStore =
            new CaffeineRefreshSessionStore(clock);
    private final AuthService service =
            new AuthService(userAuthenticationService, jwtProvider, refreshSessionStore);

    @BeforeEach
    void userAuthenticationService() {
        when(userAuthenticationService.authenticate("user", "password"))
                .thenReturn(new AuthenticatedUser(1L, UserRole.USER));
        when(userAuthenticationService.findActiveForUpdate(1L))
                .thenReturn(new AuthenticatedUser(1L, UserRole.USER));
    }

    @Test
    void loginCreatesIndependentSessionsAndRefreshReloadsRole() {
        TokenPair first = service.login("user", "password");
        TokenPair second = service.login("user", "password");
        assertThat(jwtProvider.parseRefreshTokenPayload(first.refreshToken()).sid())
                .isNotEqualTo(jwtProvider.parseRefreshTokenPayload(second.refreshToken()).sid());
        when(userAuthenticationService.findActiveForUpdate(1L))
                .thenReturn(new AuthenticatedUser(1L, UserRole.ADMIN));
        TokenPair refreshed = service.refresh(first.refreshToken());
        assertThat(jwtProvider.parseAccessTokenPayload(refreshed.accessToken()).role())
                .isEqualTo(UserRole.ADMIN);
        service.logout(refreshed.refreshToken());
        assertThat(service.refresh(second.refreshToken())).isNotNull();
    }

    @Test
    void inactiveUserCannotRefreshAndSessionIsRevoked() {
        TokenPair tokens = service.login("user", "password");
        when(userAuthenticationService.findActiveForUpdate(1L))
                .thenThrow(new CustomException(SecurityExceptionCode.USER_NOT_ACTIVE));
        assertCode(() -> service.refresh(tokens.refreshToken()),
                SecurityExceptionCode.USER_NOT_ACTIVE);
        doReturn(new AuthenticatedUser(1L, UserRole.USER))
                .when(userAuthenticationService).findActiveForUpdate(1L);
        assertCode(() -> service.refresh(tokens.refreshToken()),
                AuthExceptionCode.REFRESH_INVALID);
    }

    @Test
    void missingOrMalformedRefreshNeverReachesUserLookup() {
        assertCode(() -> service.refresh(null), AuthExceptionCode.REFRESH_REQUIRED);
        assertCode(() -> service.refresh(""), AuthExceptionCode.REFRESH_INVALID);
        assertCode(() -> service.refresh("invalid"), AuthExceptionCode.REFRESH_INVALID);
        verifyNoInteractions(userAuthenticationService);
    }

    @Test
    void storageFailuresNeverReturnTokens() {
        RefreshSessionStore unavailable = mock(RefreshSessionStore.class);
        doThrow(new IllegalStateException("storage unavailable")).when(unavailable).create(any());
        AuthService failing = new AuthService(userAuthenticationService, jwtProvider, unavailable);
        assertThatThrownBy(() -> failing.login("user", "password"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void logoutIsIdempotentAndExistingAccessRemainsValid() {
        TokenPair tokens = service.login("user", "password");
        service.logout(tokens.refreshToken());
        service.logout(tokens.refreshToken());
        service.logout(null);
        service.logout("invalid");
        assertCode(() -> service.refresh(tokens.refreshToken()),
                AuthExceptionCode.REFRESH_INVALID);
        assertThat(jwtProvider.parseAccessTokenPayload(tokens.accessToken()).userId())
                .isEqualTo(1L);
    }

    private void assertCode(Runnable action, ExceptionCode code) {
        assertThatThrownBy(action::run).isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getExceptionCode()).isEqualTo(code);
    }
}
