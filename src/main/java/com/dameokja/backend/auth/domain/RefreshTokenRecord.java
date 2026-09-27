package com.dameokja.backend.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRecord(UUID jti, Long userId, UUID sid,
        RefreshTokenStatus status, Instant expiresAt) {
    public RefreshTokenRecord withStatus(RefreshTokenStatus next) {
        return new RefreshTokenRecord(jti, userId, sid, next, expiresAt);
    }
}
