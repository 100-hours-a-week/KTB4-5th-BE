package com.dameokja.backend.push.presentation.response;

import com.dameokja.backend.global.response.SuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PushSuccessCode implements SuccessCode {
    VAPID_KEY_RETRIEVED("PUSH-200-001", "VAPID 공개키 조회 성공");

    private final String code;
    private final String message;
}
