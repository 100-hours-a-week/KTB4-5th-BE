package com.dameokja.backend.user.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SignupRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9]{2,10}") String loginId,
        @NotBlank @Pattern(regexp = "(?s)(?=.*[a-zA-Z])(?=.*[0-9]).{8,}") String password,
        String nickname) {
    // 닉네임은 비어 있으면 UserRegistrationService가 loginId로 대체하므로 필수로 두지 않는다.
    @Override
    public String toString() { return "SignupRequest[redacted]"; }
}
