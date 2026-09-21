package com.dameokja.backend.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshSessionRecord(UUID sid, Long userId, RefreshSessionStatus status,
        UUID currentJti, Instant expiresAt) {
    public RefreshSessionRecord revoke() {
        return new RefreshSessionRecord(sid, userId, RefreshSessionStatus.REVOKED,
                currentJti, expiresAt);
    }

    public RefreshSessionRecord advance(RefreshTokenRecord token) {
        Instant retention = expiresAt.isAfter(token.expiresAt()) ? expiresAt : token.expiresAt();
        return new RefreshSessionRecord(sid, userId, status, token.jti(), retention);
    }
}
