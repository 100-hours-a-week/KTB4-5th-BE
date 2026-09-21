package com.dameokja.backend.auth.infrastructure;

import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import com.dameokja.backend.auth.domain.RefreshSessionStatus;
import com.dameokja.backend.auth.domain.RefreshTokenStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dameokja.backend.auth.infrastructure.RefreshStoreAssertions.snapshot;

class RefreshConcurrencyTest {
    private final Instant now = Instant.parse("2026-09-20T00:00:00Z");
    private final CaffeineRefreshSessionStore refreshSessionStore =
            new CaffeineRefreshSessionStore(Clock.fixed(now, ZoneOffset.UTC));

    private RefreshTokenPayload token(UUID sid) {
        return new RefreshTokenPayload(1L, sid, UUID.randomUUID(), now.plusSeconds(120));
    }

    @Test
    void simultaneousRefreshAllowsOneRotationThenRevokesEveryDevice() throws Exception {
        RefreshTokenPayload first = token(UUID.randomUUID());
        RefreshTokenPayload other = token(UUID.randomUUID());
        refreshSessionStore.create(first);
        refreshSessionStore.create(other);
        List<String> outcomes = concurrently(() -> rotate(first), () -> rotate(first));
        assertThat(outcomes).containsExactlyInAnyOrder("ROTATED", "REFRESH_TOKEN_REUSED");
        assertAllRevoked();
        assertThat(snapshot(refreshSessionStore, 1L).tokens().get(first.jti()).status())
                .isEqualTo(RefreshTokenStatus.USED);
    }

    @Test
    void simultaneousRotationAndUserRevocationCannotLeaveAnActiveToken() throws Exception {
        RefreshTokenPayload first = token(UUID.randomUUID());
        refreshSessionStore.create(first);
        concurrently(() -> rotate(first), () -> {
            refreshSessionStore.revokeAll(1L);
            return "REVOKED";
        });
        assertAllRevoked();
    }

    @Test
    void simultaneousRotationAndLogoutCannotResurrectTheSession() throws Exception {
        RefreshTokenPayload first = token(UUID.randomUUID());
        refreshSessionStore.create(first);
        concurrently(() -> rotate(first), () -> {
            refreshSessionStore.revoke(first);
            return "REVOKED";
        });
        assertAllRevoked();
    }

    @Test
    void logoutWithUsedTokenRevokesTheReplacementButPreservesAnotherDevice() {
        RefreshTokenPayload first = token(UUID.randomUUID());
        RefreshTokenPayload replacement = token(first.sid());
        RefreshTokenPayload other = token(UUID.randomUUID());
        refreshSessionStore.create(first);
        refreshSessionStore.create(other);
        refreshSessionStore.rotate(first, replacement);
        refreshSessionStore.revoke(first);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> refreshSessionStore.rotate(replacement, token(first.sid())))
                .isInstanceOf(CustomException.class);
        refreshSessionStore.rotate(other, token(other.sid()));
    }

    @Test
    void logoutBeforeRotationPreventsReplacementCreation() {
        RefreshTokenPayload first = token(UUID.randomUUID());
        RefreshTokenPayload replacement = token(first.sid());
        refreshSessionStore.create(first);
        refreshSessionStore.revoke(first);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refreshSessionStore.rotate(first,
                replacement))
                .isInstanceOf(CustomException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> refreshSessionStore.rotate(replacement, token(first.sid())))
                .isInstanceOf(CustomException.class);
    }

    private String rotate(RefreshTokenPayload first) {
        try {
            refreshSessionStore.rotate(first, token(first.sid()));
            return "ROTATED";
        } catch (CustomException exception) {
            return exception.getExceptionCode().getCode();
        }
    }

    private List<String> concurrently(Callable<String> first, Callable<String> second)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> firstRequestFuture = executor.submit(() -> {
                start.await();
                return first.call();
            });
            Future<String> secondRequestFuture = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(firstRequestFuture.get(5, TimeUnit.SECONDS), secondRequestFuture.get(5,
                    TimeUnit.SECONDS));
        }
    }

    private void assertAllRevoked() {
        UserRefreshSessions state = snapshot(refreshSessionStore, 1L);
        assertThat(state.tokens().values())
                .noneMatch(tokenRecord -> tokenRecord.status() == RefreshTokenStatus.ACTIVE);
        assertThat(state.sessions().values())
                .allMatch(sessionRecord -> sessionRecord.status() == RefreshSessionStatus.REVOKED);
    }
}
