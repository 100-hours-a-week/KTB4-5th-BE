package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.UserExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NicknamePolicy {
    private static final UserNamePolicy.ErrorCodes ERROR_CODES = new UserNamePolicy.ErrorCodes(
            UserExceptionCode.NICKNAME_REQUIRED, UserExceptionCode.NICKNAME_LENGTH_INVALID,
            UserExceptionCode.NICKNAME_FORMAT_INVALID, UserExceptionCode.NICKNAME_PROHIBITED);
    private final UserNamePolicy userNamePolicy;

    public void validate(String nickname) {
        userNamePolicy.validate(nickname, ERROR_CODES);
    }
}
