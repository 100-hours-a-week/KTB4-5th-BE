package com.dameokja.backend.auth.domain;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthExceptionCode implements ExceptionCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "AUTH-400-001", "입력 형식이 잘못됐습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-401-001", "아이디·비밀번호가 틀렸습니다."),
    LOGIN_ID_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH-404-001", "존재하지 않는 아이디입니다."),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "AUTH-401-002", "리프레시 토큰이 유효하지 않습니다."),
    REFRESH_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH-401-003", "리프레시 토큰이 필요합니다."),
    LOGOUT_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH-401-004", "로그인이 필요합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
