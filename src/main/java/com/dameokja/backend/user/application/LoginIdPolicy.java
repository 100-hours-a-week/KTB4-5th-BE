package com.dameokja.backend.user.application;

import static com.dameokja.backend.user.domain.UserExceptionCode.LOGIN_ID_PROHIBITED;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginIdPolicy {
    private final ProhibitedWordChecker prohibitedWordChecker;

    public void validate(String loginId) {
        if (prohibitedWordChecker.containsProhibitedWord(loginId)) {
            throw new CustomException(LOGIN_ID_PROHIBITED);
        }
    }
}
