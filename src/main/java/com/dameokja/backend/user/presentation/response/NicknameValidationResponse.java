package com.dameokja.backend.user.presentation.response;

import com.dameokja.backend.user.application.nickname.NicknameValidationResult;

public record NicknameValidationResponse(boolean valid, String reason) {
    public static NicknameValidationResponse from(NicknameValidationResult result) {
        return new NicknameValidationResponse(result.isValid(), result.getReason());
    }
}
