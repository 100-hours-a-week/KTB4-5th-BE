package com.dameokja.backend.auth.infrastructure;

import com.dameokja.backend.auth.domain.RefreshSessionRecord;
import com.dameokja.backend.auth.domain.RefreshSessionStatus;
import com.dameokja.backend.auth.domain.RefreshTokenRecord;
import com.dameokja.backend.auth.domain.RefreshTokenStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class UserRefreshSessions {
    private final Map<UUID, RefreshTokenRecord> tokens;
    private final Map<UUID, RefreshSessionRecord> sessions;

    UserRefreshSessions() {
        tokens = new HashMap<>();
        sessions = new HashMap<>();
    }

    UserRefreshSessions(UserRefreshSessions original) {
        tokens = new HashMap<>(original.tokens);
        sessions = new HashMap<>(original.sessions);
    }

    void create(RefreshTokenPayload payload) {
        if (sessions.containsKey(payload.sid()) || tokens.containsKey(payload.jti())) {
            throw new IllegalStateException("이미 존재하는 로그인 세션 또는 토큰입니다.");
        }
        tokens.put(payload.jti(), active(payload));
        sessions.put(payload.sid(), new RefreshSessionRecord(payload.sid(), payload.userId(),
                RefreshSessionStatus.ACTIVE, payload.jti(), payload.expiresAt()));
    }

    void rotate(RefreshTokenPayload expected, RefreshTokenPayload replacement) {
        RefreshTokenRecord previous = requireToken(expected);
        if (previous.status() == RefreshTokenStatus.USED) {
            revokeAll();
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_REUSED);
        }
        RefreshSessionRecord session = requireActiveSession(previous);
        if (tokens.containsKey(replacement.jti())) {
            throw new IllegalStateException("이미 존재하는 토큰 식별자입니다.");
        }
        tokens.put(previous.jti(), previous.withStatus(RefreshTokenStatus.USED));
        RefreshTokenRecord next = active(replacement);
        tokens.put(next.jti(), next);
        sessions.put(session.sid(), session.advance(next));
    }

    private RefreshTokenRecord requireToken(RefreshTokenPayload payload) {
        RefreshTokenRecord token = tokens.get(payload.jti());
        if (token == null || !token.userId().equals(payload.userId())
                || !token.sid().equals(payload.sid())
                || !token.expiresAt().equals(payload.expiresAt())) {
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_INVALID);
        }
        return token;
    }

    private RefreshSessionRecord requireActiveSession(RefreshTokenRecord token) {
        RefreshSessionRecord session = sessions.get(token.sid());
        if (session == null) {
            throw new CustomException(SecurityExceptionCode.REFRESH_SESSION_MISSING);
        }
        if (token.status() == RefreshTokenStatus.REVOKED
                || session.status() == RefreshSessionStatus.REVOKED) {
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_REVOKED);
        }
        if (!session.userId().equals(token.userId()) || !session.currentJti().equals(token.jti())) {
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_INVALID);
        }
        return session;
    }

    void revoke(RefreshTokenPayload payload) {
        RefreshTokenRecord token = tokens.get(payload.jti());
        if (token == null || !token.userId().equals(payload.userId())
                || !token.sid().equals(payload.sid())) {
            return;
        }
        sessions.computeIfPresent(token.sid(), (sid, session) -> session.revoke());
        tokens.replaceAll((jti, current) -> current.sid().equals(token.sid())
                ? revokeActive(current) : current);
    }

    void revokeAll() {
        sessions.replaceAll((sid, session) -> session.revoke());
        tokens.replaceAll((jti, token) -> revokeActive(token));
    }

    void removeExpired(Instant now) {
        tokens.values().removeIf(token -> !now.isBefore(token.expiresAt()));
        sessions.values().removeIf(session -> !now.isBefore(session.expiresAt()));
    }

    Instant expiresAt() {
        return sessions.values().stream().map(RefreshSessionRecord::expiresAt)
                .max(Instant::compareTo).orElse(Instant.EPOCH);
    }

    Map<UUID, RefreshTokenRecord> tokens() { return Map.copyOf(tokens); }

    Map<UUID, RefreshSessionRecord> sessions() { return Map.copyOf(sessions); }

    Set<UUID> sessionIds() { return Set.copyOf(sessions.keySet()); }

    boolean isEmpty() { return sessions.isEmpty(); }

    private RefreshTokenRecord active(RefreshTokenPayload payload) {
        return new RefreshTokenRecord(payload.jti(), payload.userId(), payload.sid(),
                RefreshTokenStatus.ACTIVE, payload.expiresAt());
    }

    private RefreshTokenRecord revokeActive(RefreshTokenRecord token) {
        return token.status() == RefreshTokenStatus.ACTIVE
                ? token.withStatus(RefreshTokenStatus.REVOKED) : token;
    }
}
