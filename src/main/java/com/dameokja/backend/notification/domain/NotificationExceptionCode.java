package com.dameokja.backend.notification.domain;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum NotificationExceptionCode implements ExceptionCode {
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "NOTI-400-001", "알림 조회 요청이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOTI-404-001", "알림을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
