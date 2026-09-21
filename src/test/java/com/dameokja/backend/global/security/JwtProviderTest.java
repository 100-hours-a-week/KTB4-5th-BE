package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {
    static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC0zMi1ieXRlcy1sb25nISE=";
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private final JwtProvider jwtProvider = providerAt(NOW);

    private JwtProvider providerAt(Instant instant) {
        return new JwtProvider(SECRET, Duration.ofMinutes(15), Duration.ofDays(2),
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void roundTripsBothTokenTypes() {
        String access = jwtProvider.createAccessToken(1L, UserRole.USER);
        assertThat(jwtProvider.parseAccessTokenPayload(access))
                .isEqualTo(new AccessTokenPayload(1L, UserRole.USER));
        UUID sid = UUID.randomUUID();
        String refresh = jwtProvider.createRefreshToken(1L, sid);
        UUID jti = jwtProvider.parseRefreshTokenPayload(refresh).jti();
        assertThat(jti.version()).isEqualTo(4);
        assertThat(jwtProvider.parseRefreshTokenPayload(refresh))
                .isEqualTo(new RefreshTokenPayload(1L, sid, jti, NOW.plus(Duration.ofDays(2))));
    }

    @Test
    void distinguishesExpiryIncludingExactBoundary() {
        String token = jwtProvider.createAccessToken(1L, UserRole.USER);
        assertThat(providerAt(NOW.plusSeconds(899)).parseAccessTokenPayload(token).userId())
                .isEqualTo(1L);
        assertCode(() -> providerAt(NOW.plusSeconds(900)).parseAccessTokenPayload(token),
                SecurityExceptionCode.ACCESS_TOKEN_EXPIRED);
    }

    @Test
    void rejectsWrongTypeMalformedAndTamperedTokens() {
        String refresh = jwtProvider.createRefreshToken(1L, UUID.randomUUID());
        assertCode(() -> jwtProvider.parseAccessTokenPayload(refresh),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        assertCode(() -> jwtProvider.parseAccessTokenPayload(""),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        String access = jwtProvider.createAccessToken(1L, UserRole.USER);
        assertCode(() -> jwtProvider.parseAccessTokenPayload("x" + access),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        assertCode(() -> jwtProvider.parseRefreshTokenPayload(access),
                SecurityExceptionCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void rejectsMissingAndInvalidClaims() {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        String missing = Jwts.builder().subject("1").claim("type", "access")
                .signWith(key).compact();
        assertCode(() -> jwtProvider.parseAccessTokenPayload(missing),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        String invalid = Jwts.builder().subject("-1").claim("type", "access")
                .claim("role", "USER").issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();
        assertCode(() -> jwtProvider.parseAccessTokenPayload(invalid),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    void rejectsExpiredRefreshAtBoundary() {
        String token = jwtProvider.createRefreshToken(1L, UUID.randomUUID());
        assertCode(() -> providerAt(NOW.plus(Duration.ofDays(2)))
                .parseRefreshTokenPayload(token), SecurityExceptionCode.REFRESH_TOKEN_EXPIRED);
    }

    @Test
    void rejectsDifferentSigningKeyAndUnsupportedAlgorithm() {
        var other = Keys.hmacShaKeyFor(new byte[32]);
        String forged = Jwts.builder().subject("1").claim("type", "access")
                .claim("role", "USER").issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60))).signWith(other).compact();
        assertCode(() -> jwtProvider.parseAccessTokenPayload(forged),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        var strongerKey = Keys.hmacShaKeyFor(new byte[64]);
        String algorithm = Jwts.builder().subject("1").claim("type", "access")
                .claim("role", "USER").issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60))).signWith(strongerKey).compact();
        assertCode(() -> jwtProvider.parseAccessTokenPayload(algorithm),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    void rejectsUnknownRolesAndMalformedRefreshClaims() {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        String invalidRole = Jwts.builder().subject("1").claim("type", "access")
                .claim("role", "UNKNOWN").issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();
        assertCode(() -> jwtProvider.parseAccessTokenPayload(invalidRole),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        String invalidSid = Jwts.builder().subject("1").claim("type", "refresh")
                .claim("sid", "invalid").id(UUID.randomUUID().toString())
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(key).compact();
        assertCode(() -> jwtProvider.parseRefreshTokenPayload(invalidSid),
                SecurityExceptionCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void refreshCreatesFreshUuidV4WithoutChangingSession() {
        UUID sid = UUID.randomUUID();
        var first = jwtProvider.parseRefreshTokenPayload(jwtProvider.createRefreshToken(1L, sid));
        var second = jwtProvider.parseRefreshTokenPayload(jwtProvider.createRefreshToken(1L, sid));
        assertThat(second.sid()).isEqualTo(first.sid());
        assertThat(second.jti()).isNotEqualTo(first.jti());
        assertThat(second.jti().version()).isEqualTo(4);
        assertThat(second.jti().variant()).isEqualTo(2);
    }

    @Test
    void expiredWrongPurposeTokenIsInvalidInsteadOfExpiredAccess() {
        String refresh = jwtProvider.createRefreshToken(1L, UUID.randomUUID());
        assertCode(() -> providerAt(NOW.plus(Duration.ofDays(3)))
                .parseAccessTokenPayload(refresh), SecurityExceptionCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    void userIdCustomClaimDoesNotReplaceRequiredSubject() {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        String missingSubject = Jwts.builder().claim("userId", 1L).claim("type", "access")
                .claim("role", "USER").issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60))).signWith(key).compact();
        assertCode(() -> jwtProvider.parseAccessTokenPayload(missingSubject),
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
    }

    private void assertCode(Runnable action, SecurityExceptionCode code) {
        assertThatThrownBy(action::run).isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getExceptionCode()).isEqualTo(code);
    }
}
