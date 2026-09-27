package com.dameokja.backend.auth.infrastructure;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Repository;

@Repository
public class CaffeineRefreshSessionStore implements RefreshSessionStore {
    private final Clock clock;
    private final Cache<Long, UserRefreshSessions> userSessionsCache;

    public CaffeineRefreshSessionStore(Clock clock) {
        this.clock = clock;
        // 용량 퇴거로 USED 재사용 증거가 사라지지 않도록 만료 시각까지 보관한다.
        userSessionsCache = Caffeine.newBuilder().scheduler(Scheduler.systemScheduler())
                .expireAfter(new UserSessionExpiry(clock)).build();
    }

    @Override
    public void create(RefreshTokenPayload token) {
        requireUnexpired(token);
        update(token.userId(), state -> state.create(token));
    }

    @Override
    public void rotate(RefreshTokenPayload expected, RefreshTokenPayload replacement) {
        requireUnexpired(expected);
        requireUnexpired(replacement);
        if (!expected.userId().equals(replacement.userId())
                || !expected.sid().equals(replacement.sid())
                || expected.jti().equals(replacement.jti())) {
            throw new IllegalArgumentException("갱신은 세션을 유지하고 새 jti를 사용해야 합니다.");
        }
        boolean[] reused = {false};
        update(expected.userId(), state -> {
            try {
                state.rotate(expected, replacement);
            } catch (CustomException exception) {
                if (exception.getExceptionCode() != SecurityExceptionCode.REFRESH_TOKEN_REUSED) {
                    throw exception;
                }
                // 전체 폐기 결과를 compute에 반영한 뒤 재사용 오류를 전달한다.
                reused[0] = true;
            }
        });
        if (reused[0]) {
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_REUSED);
        }
    }

    @Override
    public void revoke(RefreshTokenPayload token) {
        requireUnexpired(token);
        update(token.userId(), state -> state.revoke(token));
    }

    @Override
    public void revokeAll(Long userId) {
        update(userId, UserRefreshSessions::revokeAll);
    }

    private void update(Long userId, Consumer<UserRefreshSessions> updateSessions) {
        userSessionsCache.asMap().compute(userId, (cachedUserId, currentSessions) -> {
            UserRefreshSessions updatedSessions = copyActive(currentSessions);
            updateSessions.accept(updatedSessions);
            return updatedSessions.isEmpty() ? null : updatedSessions;
        });
    }

    private UserRefreshSessions copyActive(UserRefreshSessions currentSessions) {
        UserRefreshSessions updatedSessions = currentSessions == null
                ? new UserRefreshSessions() : new UserRefreshSessions(currentSessions);
        updatedSessions.removeExpired(clock.instant());
        return updatedSessions;
    }

    private void requireUnexpired(RefreshTokenPayload token) {
        if (!clock.instant().isBefore(token.expiresAt())) {
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_EXPIRED);
        }
    }

    private record UserSessionExpiry(Clock clock) implements Expiry<Long, UserRefreshSessions> {
        @Override
        public long expireAfterCreate(Long userId, UserRefreshSessions userSessions,
                long currentTime) {
            return remaining(userSessions);
        }

        @Override
        public long expireAfterUpdate(Long userId, UserRefreshSessions userSessions,
                long currentTime, long currentDuration) {
            return remaining(userSessions);
        }

        @Override
        public long expireAfterRead(Long userId, UserRefreshSessions userSessions,
                long currentTime, long currentDuration) {
            return currentDuration;
        }

        private long remaining(UserRefreshSessions userSessions) {
            Instant retentionExpiresAt = userSessions.expiresAt();
            return Math.max(0, Duration.between(clock.instant(), retentionExpiresAt).toNanos());
        }
    }
}
