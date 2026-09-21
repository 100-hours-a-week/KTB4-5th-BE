package com.dameokja.backend.auth.infrastructure;

import com.dameokja.backend.auth.domain.RefreshSessionStatus;
import com.dameokja.backend.auth.domain.RefreshTokenStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRefreshSessionsTest {
    private final UserRefreshSessions userRefreshSessions = new UserRefreshSessions();
    private final Instant now = Instant.parse("2026-09-20T00:00:00Z");

    private RefreshTokenPayload token(UUID sid) {
        return new RefreshTokenPayload(1L, sid, UUID.randomUUID(), now.plusSeconds(120));
    }

    @Test
    void replayRevokesEverySessionAndPreservesUsedEvidence() {
        var first = token(UUID.randomUUID());
        var second = token(first.sid());
        var other = token(UUID.randomUUID());
        userRefreshSessions.create(first);
        userRefreshSessions.create(other);
        userRefreshSessions.rotate(first, second);
        assertThatThrownBy(() -> userRefreshSessions.rotate(first, token(first.sid())))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(
                        error.getExceptionCode())
                        .isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_REUSED));
        assertThat(userRefreshSessions.tokens().get(first.jti()).status())
                .isEqualTo(RefreshTokenStatus.USED);
        assertThat(userRefreshSessions.tokens().get(second.jti()).status())
                .isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(userRefreshSessions.tokens().get(other.jti()).status())
                .isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(userRefreshSessions.sessions().values())
                .allMatch(session -> session.status() == RefreshSessionStatus.REVOKED);
    }

    @Test
    void logoutWithPreviousTokenRevokesOnlyItsSession() {
        var first = token(UUID.randomUUID());
        var second = token(first.sid());
        var other = token(UUID.randomUUID());
        userRefreshSessions.create(first);
        userRefreshSessions.create(other);
        userRefreshSessions.rotate(first, second);
        userRefreshSessions.revoke(first);
        assertThat(userRefreshSessions.tokens().get(second.jti()).status())
                .isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(userRefreshSessions.tokens().get(other.jti()).status())
                .isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(userRefreshSessions.sessions().get(first.sid()).status())
                .isEqualTo(RefreshSessionStatus.REVOKED);
        userRefreshSessions.rotate(other, token(other.sid()));
    }
}
