package com.dameokja.backend.user.application.nickname;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NicknameValidationResult {
    AVAILABLE(true, null, "사용 가능한 닉네임"),
    INVALID_FORMAT(false, "닉네임 형식 위반", "닉네임은 2~10자, 한글·영문·숫자만 가능합니다."),
    PROHIBITED(false, "금칙어", "부적절한 닉네임입니다."),
    DUPLICATE(false, "닉네임 중복", "중복된 닉네임입니다.");

    private final boolean valid;
    private final String reason;
    private final String message;
}
