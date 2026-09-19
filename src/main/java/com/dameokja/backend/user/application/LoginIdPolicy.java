package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.UserExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginIdPolicy {
    private static final UserNamePolicy.ErrorCodes ERROR_CODES = new UserNamePolicy.ErrorCodes(
            UserExceptionCode.LOGIN_ID_REQUIRED, UserExceptionCode.LOGIN_ID_LENGTH_INVALID,
            UserExceptionCode.LOGIN_ID_FORMAT_INVALID, UserExceptionCode.LOGIN_ID_PROHIBITED);
    private final UserNamePolicy userNamePolicy;

    public void validate(String loginId) {
        userNamePolicy.validate(loginId, ERROR_CODES);
    }
}
