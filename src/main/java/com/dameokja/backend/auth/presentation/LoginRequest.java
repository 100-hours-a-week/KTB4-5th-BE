package com.dameokja.backend.auth.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9]{2,10}") String loginId,
        @NotBlank @Pattern(regexp = "(?s)(?=.*[a-zA-Z])(?=.*[0-9]).{8,}") String password) {
    public LoginRequest {
        if (loginId != null) {
            loginId = loginId.replaceAll("\\s+", "");
        }
    }

    // record 기본 출력으로 비밀번호가 노출되지 않도록 한다.
    @Override
    public String toString() { return "LoginRequest[redacted]"; }
}
