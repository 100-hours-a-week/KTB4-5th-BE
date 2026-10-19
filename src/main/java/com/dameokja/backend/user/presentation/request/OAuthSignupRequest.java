package com.dameokja.backend.user.presentation.request;

import com.dameokja.backend.user.domain.UserInputFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OAuthSignupRequest(
        @NotBlank @Pattern(regexp = UserInputFormat.NICKNAME_PATTERN) String nickname,
        @NotNull Boolean notificationSetting) {
    public OAuthSignupRequest {
        nickname = UserInputFormat.removeWhitespace(nickname);
    }
}
