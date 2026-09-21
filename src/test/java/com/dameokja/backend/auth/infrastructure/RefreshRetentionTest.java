package com.dameokja.backend.auth.infrastructure;

import com.dameokja.backend.auth.domain.RefreshSessionStatus;
import com.dameokja.backend.auth.domain.RefreshTokenStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dameokja.backend.auth.infrastructure.RefreshStoreAssertions.snapshot;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshRetentionTest {
    private final MutableClock clock = new MutableClock();
    private final Instant start = clock.instant();
    private final CaffeineRefreshSessionStore refreshSessionStore =
            new CaffeineRefreshSessionStore(clock);

    private RefreshTokenPayload token(UUID sid, long seconds) {
        return new RefreshTokenPayload(1L, sid, UUID.randomUUID(), start.plusSeconds(seconds));
    }

    @Test
    void expiredUsedReplayDoesNotRevokeOtherSessionsAndOnlyExpiredRecordsArePruned() {
        var first = token(UUID.randomUUID(), 60);
        var second = token(first.sid(), 120);
        var other = token(UUID.randomUUID(), 180);
        refreshSessionStore.create(first);
        refreshSessionStore.rotate(first, second);
        refreshSessionStore.create(other);
        clock.now = first.expiresAt();
        assertThatThrownBy(() -> refreshSessionStore.rotate(first, token(first.sid(), 180)))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error
                        .getExceptionCode())
                        .isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_EXPIRED));
        var state = snapshot(refreshSessionStore, 1L);
        assertThat(state.tokens()).doesNotContainKey(first.jti()).containsKeys(second.jti());
        assertThat(state.sessionIds()).containsExactlyInAnyOrder(first.sid(), other.sid());
        assertThat(state.tokens().get(other.jti()).status()).isEqualTo(RefreshTokenStatus.ACTIVE);
        clock.now = second.expiresAt();
        assertThat(snapshot(refreshSessionStore, 1L).sessionIds()).containsExactly(other.sid());
        clock.now = other.expiresAt();
        assertThat(snapshot(refreshSessionStore, 1L).sessionIds()).isEmpty();
        assertThat(snapshot(refreshSessionStore, 1L).tokens()).isEmpty();
    }

    @Test
    void revocationDoesNotExtendRetentionAndSessionKeepsLatestOriginalExpiry() {
        var first = token(UUID.randomUUID(), 120);
        var shorter = token(first.sid(), 90);
        refreshSessionStore.create(first);
        refreshSessionStore.rotate(first, shorter);
        assertThat(snapshot(refreshSessionStore, 1L).expiresAt()).isEqualTo(first.expiresAt());
        clock.now = start.plusSeconds(30);
        refreshSessionStore.revoke(shorter);
        assertThat(snapshot(refreshSessionStore, 1L).expiresAt()).isEqualTo(first.expiresAt());
        clock.now = shorter.expiresAt();
        var state = snapshot(refreshSessionStore, 1L);
        assertThat(state.tokens()).containsOnlyKeys(first.jti());
        assertThat(state.tokens().get(first.jti()).status()).isEqualTo(RefreshTokenStatus.USED);
        assertThat(state.sessions().get(first.sid()).status())
                .isEqualTo(RefreshSessionStatus.REVOKED);
        clock.now = first.expiresAt();
        assertThat(snapshot(refreshSessionStore, 1L).isEmpty()).isTrue();
    }

    @Test
    void readsAndRevokeAllNeverExtendUserIndexLifetime() {
        var first = token(UUID.randomUUID(), 60);
        var second = token(UUID.randomUUID(), 120);
        refreshSessionStore.create(first);
        refreshSessionStore.create(second);
        clock.now = start.plusSeconds(30);
        assertThat(snapshot(refreshSessionStore, 1L).expiresAt()).isEqualTo(second.expiresAt());
        refreshSessionStore.revokeAll(1L);
        assertThat(snapshot(refreshSessionStore, 1L).expiresAt()).isEqualTo(second.expiresAt());
        clock.now = first.expiresAt();
        assertThat(snapshot(refreshSessionStore, 1L).sessionIds()).containsExactly(second.sid());
        clock.now = second.expiresAt();
        assertThat(snapshot(refreshSessionStore, 1L).isEmpty()).isTrue();
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override
        public Instant instant() { return now; }
    }
}
