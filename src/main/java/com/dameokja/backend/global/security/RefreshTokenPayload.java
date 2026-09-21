package com.dameokja.backend.global.security;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenPayload(
        Long userId,
        UUID sid,
        UUID jti,
        // USED/REVOKED 이력도 JWT의 원래 만료 시각까지 보관한다.
        Instant expiresAt
) {
}
