package com.dameokja.backend.user.application;

import static com.dameokja.backend.user.domain.UserExceptionCode.LOGIN_ID_PROHIBITED;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 형식은 요청 DTO에서 검사하고, 여기서는 형식이 맞아도 쓸 수 없는 값만 거절한다.
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
