package com.dameokja.backend.user.application;

import static com.dameokja.backend.user.domain.UserExceptionCode.NICKNAME_PROHIBITED;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NicknamePolicy {
    private final ProhibitedWordChecker prohibitedWordChecker;

    public void validate(String nickname) {
        if (isProhibited(nickname)) {
            throw new CustomException(NICKNAME_PROHIBITED);
        }
    }

    public boolean isProhibited(String nickname) {
        return prohibitedWordChecker.containsProhibitedWord(nickname);
    }
}
