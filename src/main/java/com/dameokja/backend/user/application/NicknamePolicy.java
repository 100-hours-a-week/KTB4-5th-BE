package com.dameokja.backend.user.application;

import static com.dameokja.backend.user.domain.UserExceptionCode.NICKNAME_REQUIRED;
import static com.dameokja.backend.user.domain.UserExceptionCode.NICKNAME_LENGTH_INVALID;
import static com.dameokja.backend.user.domain.UserExceptionCode.NICKNAME_FORMAT_INVALID;
import static com.dameokja.backend.user.domain.UserExceptionCode.NICKNAME_PROHIBITED;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NicknamePolicy {
    private final NicknameAndLoginIdValidator nicknameAndLoginIdValidator;

    public void validate(String nickname) {
        nicknameAndLoginIdValidator.validateRequired(nickname, NICKNAME_REQUIRED);
        nicknameAndLoginIdValidator.validateLength(nickname, NICKNAME_LENGTH_INVALID);
        nicknameAndLoginIdValidator.validateCharacters(nickname, NICKNAME_FORMAT_INVALID);
        nicknameAndLoginIdValidator.validateProhibitedWord(nickname, NICKNAME_PROHIBITED);
    }
}
