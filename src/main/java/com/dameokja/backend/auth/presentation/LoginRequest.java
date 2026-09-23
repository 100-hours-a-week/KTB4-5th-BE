package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.global.validation.MaxUtf8Bytes;
import com.dameokja.backend.user.domain.UserInputFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank @Pattern(regexp = UserInputFormat.LOGIN_ID_PATTERN) String loginId,
        @NotBlank @Pattern(regexp = UserInputFormat.PASSWORD_PATTERN)
        @MaxUtf8Bytes(UserInputFormat.PASSWORD_MAX_BYTES) String password) {
    public LoginRequest {
        loginId = UserInputFormat.removeWhitespace(loginId);
    }

    // record 기본 출력으로 비밀번호가 노출되지 않도록 한다.
    @Override
    public String toString() { return "LoginRequest[redacted]"; }
}
