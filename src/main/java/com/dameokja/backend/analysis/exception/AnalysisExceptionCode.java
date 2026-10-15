package com.dameokja.backend.analysis.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AnalysisExceptionCode implements ExceptionCode {
    INVALID_REGISTRATION(HttpStatus.BAD_REQUEST, "IMAGE-400-002", "등록할 수 없는 분석 결과입니다."),
    CALLBACK_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "IMAGE-401-001", "서비스 인증에 실패했습니다."),
    RESULT_CONFLICT(HttpStatus.CONFLICT, "IMAGE-409-001", "이미 저장한 분석 결과와 충돌합니다."),
    CALLBACK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE-503-003", "분석 결과를 일시적으로 수신할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "IMAGE-404-001", "조회 대상을 찾을 수 없습니다."),
    REQUEST_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "IMAGE-429-001", "요청 횟수 제한"),
    ACCEPTANCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE-503-001", "분석 접수 일시 불가"),
    LOOKUP_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "IMAGE-429-002", "조회 횟수 제한을 초과했습니다."),
    LOOKUP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE-503-002", "분석 조회 일시 불가");

    @Override
    public boolean isRetryable() { return this == CALLBACK_UNAVAILABLE; }

    private final HttpStatus status;
    private final String code;
    private final String message;
}
