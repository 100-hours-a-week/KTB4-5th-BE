package com.dameokja.backend.auth.infrastructure;

import com.dameokja.backend.auth.domain.RefreshSessionRecord;
import com.dameokja.backend.auth.domain.RefreshTokenRecord;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@Primary
@RequiredArgsConstructor
public class RedisRefreshSessionStore implements RefreshSessionStore {
    private static final int MAX_ATTEMPTS = 32;
    private static final RedisScript<Long> REPLACE = RedisScript.of(
            new ClassPathResource("redis/auth/replace-refresh-sessions.lua"), Long.class);
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Override
    public void create(RefreshTokenPayload token) {
        requireUnexpired(token);
        update(token.userId(), state -> state.create(token));
    }

    @Override
    public void rotate(RefreshTokenPayload expected, RefreshTokenPayload replacement) {
        requireUnexpired(expected);
        requireUnexpired(replacement);
        if (!expected.userId().equals(replacement.userId()) || !expected.sid().equals(replacement.sid())
                || expected.jti().equals(replacement.jti())) {
            throw new IllegalArgumentException("갱신은 세션을 유지하고 새 jti를 사용해야 합니다.");
        }
        update(expected.userId(), state -> state.rotate(expected, replacement));
    }

    @Override
    public void revoke(RefreshTokenPayload token) {
        requireUnexpired(token);
        update(token.userId(), state -> state.revoke(token));
    }

    @Override
    public void revokeAll(Long userId) { update(userId, UserRefreshSessions::revokeAll); }

    private void update(Long userId, Consumer<UserRefreshSessions> transition) {
        String key = "auth:refresh:{" + userId + "}";
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String previous = redis.opsForValue().get(key);
            UserRefreshSessions state = read(previous);
            Instant previousExpiry = state.expiresAt();
            state.removeExpired(clock.instant());
            CustomException reused = apply(state, transition);
            if (replace(key, previous, state, previousExpiry)) {
                if (reused != null) {
                    throw reused;
                }
                return;
            }
        }
        throw new ConcurrencyFailureException("Refresh 세션 갱신이 동시 변경과 충돌했습니다.");
    }

    private CustomException apply(UserRefreshSessions state, Consumer<UserRefreshSessions> transition) {
        try {
            transition.accept(state);
            return null;
        } catch (CustomException error) {
            if (error.getExceptionCode() != SecurityExceptionCode.REFRESH_TOKEN_REUSED) {
                throw error;
            }
            // 재사용 증거와 사용자 전체 폐기를 Redis에 저장한 후 오류를 반환한다.
            return error;
        }
    }

    private boolean replace(String key, String previous, UserRefreshSessions state, Instant previousExpiry) {
        long ttl = Duration.between(clock.instant(), state.expiresAt()).toMillis();
        String updated = state.isEmpty() || ttl <= 0 ? "" : mapper.writeValueAsString(new StoredSessions(state.tokens(), state.sessions()));
        String extended = state.expiresAt().isAfter(previousExpiry) ? "1" : "0";
        return Long.valueOf(1).equals(redis.execute(REPLACE, List.of(key), previous == null ? "" : previous,
                updated, Long.toString(ttl), extended));
    }

    private UserRefreshSessions read(String stored) {
        if (stored == null) {
            return new UserRefreshSessions();
        }
        StoredSessions state = mapper.readValue(stored, StoredSessions.class);
        return new UserRefreshSessions(state.tokens(), state.sessions());
    }

    private void requireUnexpired(RefreshTokenPayload token) {
        if (!clock.instant().isBefore(token.expiresAt())) {
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_EXPIRED);
        }
    }

    record StoredSessions(Map<UUID, RefreshTokenRecord> tokens, Map<UUID, RefreshSessionRecord> sessions) {}
}
