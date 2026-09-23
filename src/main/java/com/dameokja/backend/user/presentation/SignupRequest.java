package com.dameokja.backend.user.presentation;

import com.dameokja.backend.user.domain.UserInputFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SignupRequest(
        @NotBlank @Pattern(regexp = UserInputFormat.LOGIN_ID_PATTERN) String loginId,
        @NotBlank @Pattern(regexp = UserInputFormat.PASSWORD_PATTERN) String password,
        @Pattern(regexp = UserInputFormat.NICKNAME_PATTERN) String nickname) {
    public SignupRequest {
        loginId = UserInputFormat.removeWhitespace(loginId);
        nickname = UserInputFormat.removeWhitespace(nickname);
        // 닉네임은 비어 있으면 UserRegistrationService가 loginId로 대체하므로 필수로 두지 않는다.
        if (nickname != null && nickname.isEmpty()) {
            nickname = null;
        }
    }

    @Override
    public String toString() { return "SignupRequest[redacted]"; }
}
