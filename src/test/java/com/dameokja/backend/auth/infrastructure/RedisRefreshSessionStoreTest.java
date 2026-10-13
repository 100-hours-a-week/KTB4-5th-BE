package com.dameokja.backend.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.auth.domain.RefreshTokenRecord;
import com.dameokja.backend.auth.domain.RefreshTokenStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class RedisRefreshSessionStoreTest {
    private static final String KEY = "auth:refresh:{1}";
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-11T00:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RedisRefreshSessionStore first = new RedisRefreshSessionStore(clock, redisTemplate(), mapper);
    private final RedisRefreshSessionStore second = new RedisRefreshSessionStore(clock, redisTemplate(), mapper);

    @BeforeEach
    void setup() { when(clock.instant()).thenReturn(now); }

    @Test
    void revocationDoesNotExtendZeroMillisecondRedisExpiry() {
        RedisScript<?> script = (RedisScript<?>) ReflectionTestUtils.getField(RedisRefreshSessionStore.class, "REPLACE");
        String boundary = """
                local time = redis.call('TIME')
                local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
                redis.call('SET', KEYS[1], ARGV[1], 'PXAT', now)
                if redis.call('PTTL', KEYS[1]) ~= 0 then return -1 end
                """;
        Long result = redisTemplate().execute(RedisScript.of(boundary + script.getScriptAsString(), Long.class),
                List.of(KEY), "before", "after", "60000", "0");
        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate().hasKey(KEY)).isFalse();
    }

    @Test
    void sharesRotationAndPersistsUserRevocationBeforeReportingReplay() {
        RefreshTokenPayload original = token(UUID.randomUUID(), 60);
        RefreshTokenPayload replacement = token(original.sid(), 120);
        RefreshTokenPayload other = token(UUID.randomUUID(), 180);
        first.create(original);
        first.create(other);
        second.rotate(original, replacement);
        assertThatThrownBy(() -> first.rotate(original, token(original.sid(), 180)))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getExceptionCode()).isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_REUSED));
        assertNoActiveTokens();
        assertThat(snapshot().tokens().get(original.jti()).status()).isEqualTo(RefreshTokenStatus.USED);
        assertThat(redisTemplate().getExpire(KEY, TimeUnit.MILLISECONDS)).isBetween(1L, 180000L);
    }

    @Test
    void concurrentRotationsRevokeEveryDeviceAfterOneSuccess() throws Exception {
        RefreshTokenPayload original = token(UUID.randomUUID(), 120);
        first.create(original);
        first.create(token(UUID.randomUUID(), 120));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> one = executor.submit(() -> rotate(first, original, start));
            Future<String> two = executor.submit(() -> rotate(second, original, start));
            start.countDown();
            assertThat(List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ROTATED", "REFRESH_TOKEN_REUSED");
        }
        assertNoActiveTokens();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRotationAndRevocationCannotResurrectTokens(boolean allSessions) throws Exception {
        RefreshTokenPayload original = token(UUID.randomUUID(), 120);
        first.create(original);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> rotation = executor.submit(() -> rotate(first, original, start));
            Future<?> revocation = executor.submit(() -> {
                start.await();
                if (allSessions) {
                    second.revokeAll(1L);
                } else {
                    second.revoke(original);
                }
                return null;
            });
            start.countDown();
            rotation.get(5, TimeUnit.SECONDS);
            revocation.get(5, TimeUnit.SECONDS);
        }
        assertNoActiveTokens();
    }

    @Test
    void logoutWithUsedTokenPreservesReplayEvidenceAndOtherDevice() {
        RefreshTokenPayload original = token(UUID.randomUUID(), 120);
        RefreshTokenPayload replacement = token(original.sid(), 180);
        RefreshTokenPayload other = token(UUID.randomUUID(), 180);
        first.create(original);
        first.create(other);
        first.rotate(original, replacement);
        second.revoke(original);
        assertThat(snapshot().tokens().get(original.jti()).status()).isEqualTo(RefreshTokenStatus.USED);
        assertThatThrownBy(() -> first.rotate(replacement, token(original.sid(), 180))).isInstanceOf(CustomException.class);
        second.rotate(other, token(other.sid(), 180));
    }

    @Test
    void shorterRotationAndRevocationKeepOriginalExpiryAndPruneExpiredRecords() {
        RefreshTokenPayload original = token(UUID.randomUUID(), 120);
        RefreshTokenPayload shorter = token(original.sid(), 60);
        first.create(original);
        Long before = redisTemplate().getExpire(KEY, TimeUnit.MILLISECONDS);
        second.rotate(original, shorter);
        assertThat(snapshot().sessions().get(original.sid()).expiresAt()).isEqualTo(original.expiresAt());
        second.revokeAll(1L);
        assertThat(redisTemplate().getExpire(KEY, TimeUnit.MILLISECONDS)).isBetween(1L, before);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        first.revokeAll(1L);
        assertThat(snapshot().tokens()).containsOnlyKeys(original.jti());
        when(clock.instant()).thenReturn(now.plusSeconds(120));
        first.revokeAll(1L);
        assertThat(redisTemplate().hasKey(KEY)).isFalse();
    }

    @Test
    void expiredReplayAndUnknownTokenDoNotRevokeOtherSessions() {
        RefreshTokenPayload original = token(UUID.randomUUID(), 60);
        RefreshTokenPayload other = token(UUID.randomUUID(), 180);
        first.create(original);
        first.rotate(original, token(original.sid(), 120));
        first.create(other);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThatThrownBy(() -> second.rotate(original, token(original.sid(), 180)))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getExceptionCode()).isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_EXPIRED));
        assertThatThrownBy(() -> second.rotate(token(other.sid(), 180), token(other.sid(), 180)))
                .isInstanceOfSatisfying(CustomException.class, error -> assertThat(error.getExceptionCode()).isEqualTo(SecurityExceptionCode.REFRESH_TOKEN_INVALID));
        assertThat(snapshot().tokens().get(other.jti()).status()).isEqualTo(RefreshTokenStatus.ACTIVE);
    }

    private String rotate(RedisRefreshSessionStore store, RefreshTokenPayload original, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            store.rotate(original, token(original.sid(), 180));
            return "ROTATED";
        } catch (CustomException error) {
            return error.getExceptionCode().getCode();
        }
    }

    private void assertNoActiveTokens() {
        assertThat(snapshot().tokens().values()).extracting(RefreshTokenRecord::status).doesNotContain(RefreshTokenStatus.ACTIVE);
    }

    private RedisRefreshSessionStore.StoredSessions snapshot() {
        return mapper.readValue(redisTemplate().opsForValue().get(KEY), RedisRefreshSessionStore.StoredSessions.class);
    }

    private RefreshTokenPayload token(UUID sid, long seconds) {
        return new RefreshTokenPayload(1L, sid, UUID.randomUUID(), now.plusSeconds(seconds));
    }
}
