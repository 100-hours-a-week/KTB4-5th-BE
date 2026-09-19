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

    void validate(String userName, ErrorCodes errorCodes) {
        if (userName == null || userName.isBlank()) {
            throw new CustomException(errorCodes.required());
        }
        if (userName.length() < MIN_LENGTH || userName.length() > MAX_LENGTH) {
            throw new CustomException(errorCodes.lengthInvalid());
        }
        if (!ALLOWED_CHARACTERS.matcher(userName).matches()) {
            throw new CustomException(errorCodes.formatInvalid());
        }
        if (prohibitedWordChecker.containsProhibitedWord(userName)) {
            throw new CustomException(errorCodes.prohibited());
        }
    }

    record ErrorCodes(UserExceptionCode required, UserExceptionCode lengthInvalid,
            UserExceptionCode formatInvalid, UserExceptionCode prohibited) {}
}
