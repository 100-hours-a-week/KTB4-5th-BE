package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class OAuthRegistrationStore {
    private static final Duration LIFETIME = Duration.ofSeconds(600);
    private static final int MAX_ATTEMPTS = 32;
    private static final String PREFIX = "oauth:registration:";
    private static final RedisScript<Long> REPLACE = RedisScript.of(
            new ClassPathResource("redis/user/replace-registration.lua"), Long.class);
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public OAuthRegistrationStore(Clock clock, StringRedisTemplate redis, ObjectMapper mapper) {
        this.clock = clock;
        this.redis = redis;
        this.mapper = mapper;
    }

    public String create(OAuthIdentity identity) {
        UUID token = UUID.randomUUID();
        PendingRegistration pending = new PendingRegistration(identity, clock.instant().plus(LIFETIME), null);
        redis.opsForValue().set(PREFIX + token, mapper.writeValueAsString(pending), LIFETIME);
        return token.toString();
    }

    public RegistrationClaim claim(String token) {
        UUID key = parse(token);
        UUID claimId = UUID.randomUUID();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String stored = redis.opsForValue().get(PREFIX + key);
            PendingRegistration pending = read(stored);
            if (pending == null || !clock.instant().isBefore(pending.expiresAt()) || pending.claimId() != null) {
                throw invalid();
            }
            PendingRegistration claimed = new PendingRegistration(pending.identity(), pending.expiresAt(), claimId);
            if (replace(key, stored, claimed)) {
                return new RegistrationClaim(key, claimId, pending.identity());
            }
        }
        throw new ConcurrencyFailureException("가입 대기 토큰 선점이 동시 변경과 충돌했습니다.");
    }

    public void consume(RegistrationClaim claim) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String stored = redis.opsForValue().get(PREFIX + claim.token());
            PendingRegistration pending = read(stored);
            if (pending == null || !claim.claimId().equals(pending.claimId())) {
                return;
            }
            if (replace(claim.token(), stored, null)) {
                return;
            }
        }
        throw new ConcurrencyFailureException("가입 대기 토큰 소비가 동시 변경과 충돌했습니다.");
    }

    public void release(RegistrationClaim claim) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String stored = redis.opsForValue().get(PREFIX + claim.token());
            PendingRegistration pending = read(stored);
            if (pending == null || !claim.claimId().equals(pending.claimId())) {
                return;
            }
            PendingRegistration released;
            if (!clock.instant().isBefore(pending.expiresAt())) {
                released = null;
            } else {
                released = new PendingRegistration(pending.identity(), pending.expiresAt(), null);
            }
            // 실패 후 재시도도 최초 발급 시각까지의 TTL만 사용한다.
            if (replace(claim.token(), stored, released)) {
                return;
            }
        }
        throw new ConcurrencyFailureException("가입 대기 토큰 선점 해제가 동시 변경과 충돌했습니다.");
    }

    private boolean replace(UUID token, String stored, PendingRegistration pending) {
        String updated = pending == null ? "" : mapper.writeValueAsString(pending);
        return Long.valueOf(1).equals(redis.execute(REPLACE, List.of(PREFIX + token), stored, updated));
    }

    private PendingRegistration read(String stored) {
        return stored == null ? null : mapper.readValue(stored, PendingRegistration.class);
    }

    private UUID parse(String token) {
        if (token == null) {
            throw invalid();
        }
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private CustomException invalid() { return new CustomException(UserExceptionCode.REGISTRATION_INVALID); }

    private record PendingRegistration(OAuthIdentity identity, Instant expiresAt, UUID claimId) {}
}
