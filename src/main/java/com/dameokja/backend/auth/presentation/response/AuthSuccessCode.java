package com.dameokja.backend.auth.presentation.response;

import com.dameokja.backend.global.response.SuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthSuccessCode implements SuccessCode {
    LOGIN("AUTH-200-001", "로그인 성공"),
    LOGOUT("AUTH-200-002", "로그아웃 성공"),
    REFRESH("AUTH-200-003", "인증정보 갱신 성공");

    private final String code;
    private final String message;
}
