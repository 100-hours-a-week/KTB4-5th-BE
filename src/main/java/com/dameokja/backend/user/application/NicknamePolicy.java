package com.dameokja.backend.user.application;

import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NicknamePolicy {
    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 10;

    private final ProhibitedWordChecker prohibitedWordChecker;

    public void validate(String nickname) {
        validateFormat(nickname);
        if (prohibitedWordChecker.containsProhibitedWord(nickname)) {
            throw new CustomException(UserExceptionCode.NICKNAME_PROHIBITED);
        }
    }

    private void validateFormat(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new CustomException(UserExceptionCode.NICKNAME_REQUIRED);
        }
        if (nickname.length() < MIN_LENGTH || nickname.length() > MAX_LENGTH) {
            throw new CustomException(UserExceptionCode.NICKNAME_LENGTH_INVALID);
        }
        if (!UserNameCharacters.isValid(nickname)) {
            throw new CustomException(UserExceptionCode.NICKNAME_FORMAT_INVALID);
        }
    }
}
