package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {
    private final SecretKey signingKey;
    private final Duration accessTokenExpiration;
    private final Duration refreshTokenExpiration;
    private final Clock clock;
    private final JwtParser jwtParser;

    public JwtProvider(@Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") Duration accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") Duration refreshTokenExpiration,
            Clock clock) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        validateDuration(accessTokenExpiration);
        validateDuration(refreshTokenExpiration);
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.clock = clock;
        this.jwtParser =
                Jwts.parser().verifyWith(signingKey).clock(() -> Date.from(clock.instant()))
                .sig().clear().add(Jwts.SIG.HS256).and().build();
    }

    private void validateDuration(Duration duration) {
        if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("토큰 유효기간은 최소 1초입니다.");
        }
    }

    public String createAccessToken(Long userId, UserRole role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration)))
                .claim("type", "access")
                .claim("role", role.name())
                .signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    public String createRefreshToken(Long userId, UUID sid) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenExpiration)))
                .claim("type", "refresh")
                .claim("sid", sid.toString())
                .signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    public AccessTokenPayload parseAccessTokenPayload(String token) {
        try {
            Claims claims = parseToken(token, "access");
            AccessTokenPayload payload = new AccessTokenPayload(userId(claims),
                    UserRole.valueOf(required(claims, "role")));
            validateExpiration(claims, "access");
            return payload;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new CustomException(SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        }
    }

    public RefreshTokenPayload parseRefreshTokenPayload(String token) {
        try {
            Claims claims = parseToken(token, "refresh");
            RefreshTokenPayload payload = new RefreshTokenPayload(userId(claims),
                    uuid(claims, "sid"), uuid(claims, "jti"), claims.getExpiration().toInstant());
            validateExpiration(claims, "refresh");
            return payload;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new CustomException(SecurityExceptionCode.REFRESH_TOKEN_INVALID);
        }
    }

    private Claims parseToken(String token, String tokenType) {
        Claims claims = signedClaims(token);
        validateCommonClaims(claims, tokenType);
        return claims;
    }

    private void validateExpiration(Claims claims, String tokenType) {
        if (!clock.instant().isBefore(claims.getExpiration().toInstant())) {
            throw new CustomException(errorCode(tokenType, true));
        }
    }

    private Claims signedClaims(String token) {
        try {
            return jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException exception) {
            // 서명 검증 후 얻은 클레임도 용도·필수값을 확인한 뒤 만료로 분류한다.
            return exception.getClaims();
        }
    }

    private void validateCommonClaims(Claims claims, String tokenType) {
        if (!tokenType.equals(required(claims, "type")) || claims.getIssuedAt() == null
                || claims.getExpiration() == null) {
            throw new IllegalArgumentException("필수 클레임이 없거나 토큰 용도가 다릅니다.");
        }
        if (!claims.getIssuedAt().before(claims.getExpiration())) {
            throw new IllegalArgumentException("유효하지 않은 토큰 발급·만료 시각입니다.");
        }
    }

    private Long userId(Claims claims) {
        long id = Long.parseLong(required(claims, "sub"));
        if (id <= 0) {
            throw new IllegalArgumentException("유효하지 않은 사용자 ID입니다.");
        }
        return id;
    }

    private UUID uuid(Claims claims, String claimName) {
        String value = required(claims, claimName);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)
                || ("jti".equals(claimName) && (id.version() != 4 || id.variant() != 2))) {
            throw new IllegalArgumentException("유효하지 않은 토큰 식별자입니다.");
        }
        return id;
    }

    private String required(Claims claims, String claimName) {
        String value = claims.get(claimName, String.class);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 클레임이 없습니다: " + claimName);
        }
        return value;
    }

    private SecurityExceptionCode errorCode(String tokenType, boolean expired) {
        if ("access".equals(tokenType)) {
            return expired ? SecurityExceptionCode.ACCESS_TOKEN_EXPIRED
                    : SecurityExceptionCode.ACCESS_TOKEN_INVALID;
        }
        return expired ? SecurityExceptionCode.REFRESH_TOKEN_EXPIRED
                : SecurityExceptionCode.REFRESH_TOKEN_INVALID;
    }
}
