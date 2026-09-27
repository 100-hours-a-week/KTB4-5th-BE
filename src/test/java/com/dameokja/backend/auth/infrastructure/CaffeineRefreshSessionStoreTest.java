package com.dameokja.backend.auth.infrastructure;

import com.dameokja.backend.auth.domain.RefreshSessionStatus;
import com.dameokja.backend.auth.domain.RefreshTokenStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dameokja.backend.auth.infrastructure.RefreshStoreAssertions.snapshot;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaffeineRefreshSessionStoreTest {
    private final Instant now = Instant.parse("2026-09-20T00:00:00Z");
    private final CaffeineRefreshSessionStore refreshSessionStore =
            new CaffeineRefreshSessionStore(Clock.fixed(now, ZoneOffset.UTC));

    private RefreshTokenPayload token(long userId, UUID sid, long seconds) {
        return new RefreshTokenPayload(userId, sid, UUID.randomUUID(), now.plusSeconds(seconds));
    }

    @Test
    void rotationRetainsUsedRecordAndUpdatesSessionWithoutExtendingOldExpiry() {
        RefreshTokenPayload first = token(1, UUID.randomUUID(), 60);
        RefreshTokenPayload second = token(1, first.sid(), 120);
        refreshSessionStore.create(first);
        refreshSessionStore.rotate(first, second);
        UserRefreshSessions state = snapshot(refreshSessionStore, 1L);
        assertThat(state.tokens().get(first.jti()).status()).isEqualTo(RefreshTokenStatus.USED);
        assertThat(state.tokens().get(first.jti()).expiresAt()).isEqualTo(first.expiresAt());
        assertThat(state.tokens().get(second.jti()).status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(state.sessions().get(first.sid()).currentJti()).isEqualTo(second.jti());
        assertThat(state.sessions().get(first.sid()).expiresAt()).isEqualTo(second.expiresAt());
        assertThat(state.sessionIds()).containsExactly(first.sid());
    }

    @Test
    void usedReplayRevokesEverySessionOfThatUserButNotOtherUsers() {
        RefreshTokenPayload first = token(1, UUID.randomUUID(), 60);
        RefreshTokenPayload second = token(1, first.sid(), 120);
        RefreshTokenPayload otherDevice = token(1, UUID.randomUUID(), 90);
        RefreshTokenPayload otherUser = token(2, UUID.randomUUID(), 90);
        refreshSessionStore.create(first);
        refreshSessionStore.create(otherDevice);
        refreshSessionStore.create(otherUser);
        refreshSessionStore.rotate(first, second);
        assertCode(() -> refreshSessionStore.rotate(first, token(1, first.sid(), 150)),
                SecurityExceptionCode.REFRESH_TOKEN_REUSED);
        assertRevoked(1L);
        assertThat(snapshot(refreshSessionStore, 1L).tokens().get(first.jti()).status())
                .isEqualTo(RefreshTokenStatus.USED);
        refreshSessionStore.rotate(otherUser, token(2, otherUser.sid(), 120));
    }

    @Test
    void logoutRevokesOnlyOneSessionAndKeepsUsedEvidenceAndSessionIndex() {
        RefreshTokenPayload first = token(1, UUID.randomUUID(), 60);
        RefreshTokenPayload second = token(1, first.sid(), 120);
        RefreshTokenPayload other = token(1, UUID.randomUUID(), 90);
        refreshSessionStore.create(first);
        refreshSessionStore.create(other);
        refreshSessionStore.rotate(first, second);
        refreshSessionStore.revoke(second);
        UserRefreshSessions state = snapshot(refreshSessionStore, 1L);
        assertThat(state.tokens().get(first.jti()).status()).isEqualTo(RefreshTokenStatus.USED);
        assertThat(state.tokens().get(second.jti()).status()).isEqualTo(RefreshTokenStatus.REVOKED);
        assertThat(state.sessions().get(first.sid()).status())
                .isEqualTo(RefreshSessionStatus.REVOKED);
        assertThat(state.sessionIds()).containsExactlyInAnyOrder(first.sid(), other.sid());
        assertCode(() -> refreshSessionStore.rotate(second, token(1, first.sid(), 150)),
                SecurityExceptionCode.REFRESH_TOKEN_REVOKED);
        refreshSessionStore.rotate(other, token(1, other.sid(), 150));
    }

    @Test
    void usedReplayAfterLogoutStillRevokesOtherSessions() {
        RefreshTokenPayload first = token(1, UUID.randomUUID(), 60);
        RefreshTokenPayload second = token(1, first.sid(), 120);
        RefreshTokenPayload other = token(1, UUID.randomUUID(), 90);
        refreshSessionStore.create(first);
        refreshSessionStore.create(other);
        refreshSessionStore.rotate(first, second);
        refreshSessionStore.revoke(second);
        assertCode(() -> refreshSessionStore.rotate(first, token(1, first.sid(), 150)),
                SecurityExceptionCode.REFRESH_TOKEN_REUSED);
        assertRevoked(1L);
    }

    @Test
    void unknownOrMismatchedTokenDoesNotRevokeValidSessions() {
        RefreshTokenPayload active = token(1, UUID.randomUUID(), 60);
        refreshSessionStore.create(active);
        assertCode(() -> refreshSessionStore.rotate(token(1, active.sid(), 60), token(1,
                active.sid(), 120)),
                SecurityExceptionCode.REFRESH_TOKEN_INVALID);
        RefreshTokenPayload mismatch = new RefreshTokenPayload(
                1L, UUID.randomUUID(), active.jti(), active.expiresAt());
        assertCode(() -> refreshSessionStore.rotate(mismatch, token(1, mismatch.sid(), 120)),
                SecurityExceptionCode.REFRESH_TOKEN_INVALID);
        refreshSessionStore.rotate(active, token(1, active.sid(), 120));
    }

    @Test
    void revokeAllPreservesRecordsAndLaterNewLoginRemainsIndependent() {
        RefreshTokenPayload first = token(1, UUID.randomUUID(), 60);
        RefreshTokenPayload second = token(1, UUID.randomUUID(), 120);
        refreshSessionStore.create(first);
        refreshSessionStore.create(second);
        refreshSessionStore.revokeAll(1L);
        assertRevoked(1L);
        assertThat(snapshot(refreshSessionStore, 1L).expiresAt()).isEqualTo(second.expiresAt());
        RefreshTokenPayload later = token(1, UUID.randomUUID(), 150);
        refreshSessionStore.create(later);
        refreshSessionStore.rotate(later, token(1, later.sid(), 180));
        assertThat(snapshot(refreshSessionStore, 1L).sessions().get(first.sid()).status())
                .isEqualTo(RefreshSessionStatus.REVOKED);
    }

    private void assertRevoked(Long userId) {
        UserRefreshSessions state = snapshot(refreshSessionStore, userId);
        assertThat(state.sessions().values())
                .allMatch(sessionRecord -> sessionRecord.status() == RefreshSessionStatus.REVOKED);
        assertThat(state.tokens().values())
                .noneMatch(tokenRecord -> tokenRecord.status() == RefreshTokenStatus.ACTIVE);
    }

    private void assertCode(Runnable action, SecurityExceptionCode code) {
        assertThatThrownBy(action::run).isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getExceptionCode()).isEqualTo(code);
    }
}
