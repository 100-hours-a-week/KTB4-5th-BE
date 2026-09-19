package com.dameokja.backend.user.application;

import static com.dameokja.backend.user.domain.UserExceptionCode.LOGIN_ID_REQUIRED;
import static com.dameokja.backend.user.domain.UserExceptionCode.LOGIN_ID_LENGTH_INVALID;
import static com.dameokja.backend.user.domain.UserExceptionCode.LOGIN_ID_FORMAT_INVALID;
import static com.dameokja.backend.user.domain.UserExceptionCode.LOGIN_ID_PROHIBITED;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginIdPolicy {
    private final NicknameAndLoginIdValidator nicknameAndLoginIdValidator;

    public void validate(String loginId) {
        nicknameAndLoginIdValidator.validateRequired(loginId, LOGIN_ID_REQUIRED);
        nicknameAndLoginIdValidator.validateLength(loginId, LOGIN_ID_LENGTH_INVALID);
        nicknameAndLoginIdValidator.validateCharacters(loginId, LOGIN_ID_FORMAT_INVALID);
        nicknameAndLoginIdValidator.validateProhibitedWord(loginId, LOGIN_ID_PROHIBITED);
    }
}
