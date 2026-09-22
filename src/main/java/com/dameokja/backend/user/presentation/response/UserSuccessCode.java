package com.dameokja.backend.user.presentation.response;

import com.dameokja.backend.global.response.SuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserSuccessCode implements SuccessCode {
    SIGNUP("USER-201-001", "회원가입 성공");

    private final String code;
    private final String message;
}
