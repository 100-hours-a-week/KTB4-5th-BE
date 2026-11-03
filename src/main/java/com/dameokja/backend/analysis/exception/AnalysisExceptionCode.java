package com.dameokja.backend.analysis.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AnalysisExceptionCode implements ExceptionCode {
    INVALID_REGISTRATION(HttpStatus.BAD_REQUEST, "IMAGE-400-002", "등록할 수 없는 분석 결과입니다."),
    RESULT_CONFLICT(HttpStatus.CONFLICT, "IMAGE-409-001", "이미 저장한 분석 결과와 충돌합니다."),
    DELETE_IN_PROGRESS(HttpStatus.CONFLICT, "IMAGE-409-002", "진행 중인 분석 작업은 삭제할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "IMAGE-404-001", "조회 대상을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
