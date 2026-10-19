package com.dameokja.backend.user.presentation.request;

import com.dameokja.backend.user.domain.UserInputFormat;
import jakarta.validation.constraints.NotNull;

// 형식 위반도 검사 결과로 돌려주기 위해 @Pattern을 두지 않고 서비스에서 검사한다.
public record NicknameValidationRequest(@NotNull String nickname) {
    public NicknameValidationRequest {
        nickname = UserInputFormat.removeWhitespace(nickname);
    }
}
