package com.dameokja.backend.user.infrastructure;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Repository;

@Repository
public class OAuthRegistrationStore {
    private static final Duration LIFETIME = Duration.ofSeconds(600);
    private static final long MAX_PENDING_REGISTRATIONS = 10000;
    private final Cache<UUID, PendingRegistration> registrations = Caffeine.newBuilder()
            .expireAfterWrite(LIFETIME).maximumSize(MAX_PENDING_REGISTRATIONS).build();
    private final Clock clock;

    public OAuthRegistrationStore(Clock clock) { this.clock = clock; }

    public String create(OAuthIdentity identity) {
        UUID token = UUID.randomUUID();
        registrations.put(token, new PendingRegistration(identity, clock.instant().plus(LIFETIME), null));
        return token.toString();
    }

    public RegistrationClaim claim(String token) {
        UUID key = parse(token);
        UUID claimId = UUID.randomUUID();
        AtomicReference<OAuthIdentity> identity = new AtomicReference<>();
        registrations.asMap().compute(key, (ignored, pending) -> {
            if (pending == null || !clock.instant().isBefore(pending.expiresAt()) || pending.claimId() != null) {
                throw invalid();
            }
            identity.set(pending.identity());
            return new PendingRegistration(pending.identity(), pending.expiresAt(), claimId);
        });
        return new RegistrationClaim(key, claimId, identity.get());
    }

    public void consume(RegistrationClaim claim) {
        registrations.asMap().computeIfPresent(claim.token(), (ignored, pending) ->
                claim.claimId().equals(pending.claimId()) ? null : pending);
    }

    public void release(RegistrationClaim claim) {
        registrations.asMap().computeIfPresent(claim.token(), (ignored, pending) -> {
            if (!claim.claimId().equals(pending.claimId())) {
                return pending;
            }
            // 실패한 요청도 최초 발급 시각으로부터 600초까지만 재시도한다.
            return clock.instant().isBefore(pending.expiresAt())
                    ? new PendingRegistration(pending.identity(), pending.expiresAt(), null) : null;
        });
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
