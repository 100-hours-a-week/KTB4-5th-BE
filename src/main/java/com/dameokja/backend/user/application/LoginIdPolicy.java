package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.UserExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginIdPolicy {
    private static final NicknameAndLoginIdValidator.ErrorCodes ERROR_CODES = new NicknameAndLoginIdValidator.ErrorCodes(
            UserExceptionCode.LOGIN_ID_REQUIRED, UserExceptionCode.LOGIN_ID_LENGTH_INVALID,
            UserExceptionCode.LOGIN_ID_FORMAT_INVALID, UserExceptionCode.LOGIN_ID_PROHIBITED);
    private final NicknameAndLoginIdValidator nicknameAndLoginIdValidator;

    public void validate(String loginId) {
        nicknameAndLoginIdValidator.validate(loginId, ERROR_CODES);
    }
}
