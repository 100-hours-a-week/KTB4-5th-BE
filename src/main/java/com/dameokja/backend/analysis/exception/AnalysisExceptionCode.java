package com.dameokja.backend.analysis.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AnalysisExceptionCode implements ExceptionCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "IMAGE-404-001", "조회 대상을 찾을 수 없습니다."),
    REQUEST_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "IMAGE-429-001", "요청 횟수 제한"),
    ACCEPTANCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE-503-001", "분석 접수 일시 불가");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
