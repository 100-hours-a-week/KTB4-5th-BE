package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SecurityExceptionCode implements ExceptionCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증 토큰이 만료되었습니다."),
    ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "인증 토큰이 올바르지 않습니다."),
    REFRESH_TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "갱신 토큰이 필요합니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "갱신 토큰이 만료되었습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "갱신 토큰이 올바르지 않습니다."),
    REFRESH_SESSION_MISSING(HttpStatus.UNAUTHORIZED, "로그인 세션이 만료되거나 종료되었습니다."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "토큰 재사용으로 모든 로그인 세션이 종료되었습니다."),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "폐기된 토큰 또는 로그인 세션입니다."),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "CSRF 토큰이 없거나 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return this == CSRF_TOKEN_INVALID ? "COMMON-403-CSRF-001" : name();
    }
}
