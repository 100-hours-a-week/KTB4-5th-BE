package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class NicknameAndLoginIdValidator {
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[\\p{IsHangul}A-Za-z0-9]+");
    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 10;
    private final ProhibitedWordChecker prohibitedWordChecker;

    void validateRequired(String input, UserExceptionCode exceptionCode) {
        if (input == null || input.isBlank()) {
            throw new CustomException(exceptionCode);
        }
    }

    void validateLength(String input, UserExceptionCode exceptionCode) {
        if (input.length() < MIN_LENGTH || input.length() > MAX_LENGTH) {
            throw new CustomException(exceptionCode);
        }
    }

    void validateCharacters(String input, UserExceptionCode exceptionCode) {
        if (!ALLOWED_CHARACTERS.matcher(input).matches()) {
            throw new CustomException(exceptionCode);
        }
    }

    void validateProhibitedWord(String input, UserExceptionCode exceptionCode) {
        if (prohibitedWordChecker.containsProhibitedWord(input)) {
            throw new CustomException(exceptionCode);
        }
    }
}
