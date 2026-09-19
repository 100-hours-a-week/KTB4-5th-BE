package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;

final class LoginIdPolicy {
    private LoginIdPolicy() {}

    static void validate(String loginId) {
        if (loginId == null) {
            return;
        }
        if (!UserNameCharacters.isValid(loginId)) {
            throw new CustomException(UserExceptionCode.LOGIN_ID_FORMAT_INVALID);
        }
    }
}
