package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.UserExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NicknamePolicy {
    private static final NicknameAndLoginIdValidator.ErrorCodes ERROR_CODES = new NicknameAndLoginIdValidator.ErrorCodes(
            UserExceptionCode.NICKNAME_REQUIRED, UserExceptionCode.NICKNAME_LENGTH_INVALID,
            UserExceptionCode.NICKNAME_FORMAT_INVALID, UserExceptionCode.NICKNAME_PROHIBITED);
    private final NicknameAndLoginIdValidator nicknameAndLoginIdValidator;

    public void validate(String nickname) {
        nicknameAndLoginIdValidator.validate(nickname, ERROR_CODES);
    }
}
