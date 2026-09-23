package com.dameokja.backend.user.domain;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserExceptionCode implements ExceptionCode {
    USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "USER-403-001", "탈퇴한 회원은 이용할 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-404-001", "회원을 찾을 수 없습니다."),
    NICKNAME_DUPLICATE(HttpStatus.CONFLICT, "USER-409-001", "이미 사용 중인 닉네임입니다."),
    LOGIN_ID_DUPLICATE(HttpStatus.CONFLICT, "USER-409-002", "이미 사용 중인 로그인 아이디입니다."),
    NICKNAME_PROHIBITED(HttpStatus.UNPROCESSABLE_CONTENT, "USER-422-001", "사용할 수 없는 닉네임입니다."),
    LOGIN_ID_PROHIBITED(HttpStatus.UNPROCESSABLE_CONTENT, "USER-422-002", "사용할 수 없는 로그인 아이디입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
