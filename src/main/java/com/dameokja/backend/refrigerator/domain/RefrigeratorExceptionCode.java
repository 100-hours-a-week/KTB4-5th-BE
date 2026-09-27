package com.dameokja.backend.refrigerator.domain;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefrigeratorExceptionCode implements ExceptionCode {
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "REFRIGERATOR-403-001", "해당 냉장고에 접근할 수 없습니다."),
    REFRIGERATOR_NOT_FOUND(HttpStatus.NOT_FOUND, "REFRIGERATOR-404-001", "냉장고를 찾을 수 없습니다."),
    REFRIGERATOR_DELETED(HttpStatus.GONE, "REFRIGERATOR-410-001", "삭제된 냉장고입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
