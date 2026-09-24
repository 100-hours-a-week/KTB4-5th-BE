package com.dameokja.backend.push.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PushExceptionCode implements ExceptionCode {
    SUBSCRIPTION_OWNER_CONFLICT(HttpStatus.CONFLICT, "PUSH-409-001",
            "다른 계정에서 이미 사용 중인 구독입니다."),
    SUBSCRIPTION_FORBIDDEN(HttpStatus.FORBIDDEN, "PUSH-403-001", "본인 구독만 해제할 수 있습니다."),
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "PUSH-404-001", "구독을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
