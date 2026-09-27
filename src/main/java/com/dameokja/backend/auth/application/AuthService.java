package com.dameokja.backend.auth.application;

import com.dameokja.backend.auth.infrastructure.RefreshSessionStore;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.user.application.AuthenticatedUser;
import com.dameokja.backend.user.application.UserAuthenticationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserAuthenticationService userAuthenticationService;
    private final JwtProvider jwtProvider;
    private final RefreshSessionStore refreshSessionStore;

    @Transactional
    public TokenPair login(String loginId, String password) {
        AuthenticatedUser authenticatedUser =
                userAuthenticationService.authenticate(loginId, password);
        TokenPair tokenPair = issue(authenticatedUser, UUID.randomUUID());
        refreshSessionStore.create(jwtProvider.parseRefreshTokenPayload(tokenPair.refreshToken()));
        return tokenPair;
    }

    @Transactional
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null) {
            throw new CustomException(AuthExceptionCode.REFRESH_REQUIRED);
        }
        try {
            RefreshTokenPayload previous = jwtProvider.parseRefreshTokenPayload(refreshToken);
            AuthenticatedUser authenticatedUser = activeUser(previous);
            TokenPair tokenPair = issue(authenticatedUser, previous.sid());
            RefreshTokenPayload next =
                    jwtProvider.parseRefreshTokenPayload(tokenPair.refreshToken());
            refreshSessionStore.rotate(previous, next);
            return tokenPair;
        } catch (CustomException exception) {
            if (exception.getExceptionCode().getStatus().value() == 401) {
                throw new CustomException(AuthExceptionCode.REFRESH_INVALID);
            }
            throw exception;
        }
    }

    public void logout(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        try {
            refreshSessionStore.revoke(jwtProvider.parseRefreshTokenPayload(refreshToken));
        } catch (CustomException exception) {
            // 무효·만료 토큰에서는 폐기할 세션의 신뢰할 수 있는 식별자를 얻을 수 없다.
            if (exception.getExceptionCode() != SecurityExceptionCode.REFRESH_TOKEN_INVALID
                    && exception.getExceptionCode()
                            != SecurityExceptionCode.REFRESH_TOKEN_EXPIRED) {
                throw exception;
            }
        }
    }

    public void revokeAllUserSessions(Long userId) {
        refreshSessionStore.revokeAll(userId);
    }

    private AuthenticatedUser activeUser(RefreshTokenPayload previous) {
        try {
            return userAuthenticationService.findActiveForUpdate(previous.userId());
        } catch (CustomException exception) {
            refreshSessionStore.revokeAll(previous.userId());
            throw exception;
        }
    }

    private TokenPair issue(AuthenticatedUser authenticatedUser, UUID sid) {
        String accessToken = jwtProvider.createAccessToken(
                authenticatedUser.id(), authenticatedUser.role());
        String refreshToken = jwtProvider.createRefreshToken(authenticatedUser.id(), sid);
        return new TokenPair(accessToken, refreshToken, authenticatedUser.id());
    }

}
