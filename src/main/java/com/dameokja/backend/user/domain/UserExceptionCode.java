package com.dameokja.backend.user.domain;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserExceptionCode implements ExceptionCode {
    NICKNAME_REQUIRED(HttpStatus.BAD_REQUEST, "USER-400-001", "닉네임을 입력해 주세요."),
    NICKNAME_LENGTH_INVALID(HttpStatus.BAD_REQUEST, "USER-400-002", "닉네임은 2~10자로 입력해 주세요."),
    NICKNAME_FORMAT_INVALID(HttpStatus.BAD_REQUEST, "USER-400-003", "닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."),
    NICKNAME_PROHIBITED(HttpStatus.BAD_REQUEST, "USER-400-004", "사용할 수 없는 닉네임입니다."),
    LOGIN_ID_FORMAT_INVALID(HttpStatus.BAD_REQUEST, "USER-400-006",
            "로그인 아이디는 한글, 영문, 숫자만 사용할 수 있습니다."),
    LOGIN_ID_REQUIRED(HttpStatus.BAD_REQUEST, "USER-400-007", "로그인 아이디를 입력해 주세요."),
    USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "USER-403-002", "탈퇴한 회원은 이용할 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-404-001", "회원을 찾을 수 없습니다."),
    NICKNAME_DUPLICATE(HttpStatus.CONFLICT, "USER-409-001", "이미 사용 중인 닉네임입니다."),
    LOGIN_ID_DUPLICATE(HttpStatus.CONFLICT, "USER-409-002", "이미 사용 중인 로그인 아이디입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
