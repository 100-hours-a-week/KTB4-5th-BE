package com.dameokja.backend.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GlobalExceptionCode implements ExceptionCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "GLOBAL-400-001", "요청 형식이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "GLOBAL-401-001", "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "GLOBAL-403-001", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "GLOBAL-404-001", "대상을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "GLOBAL-405-001", "지원하지 않는 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "GLOBAL-406-001", "지원하지 않는 응답 형식입니다."),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "GLOBAL-409-001", "멱등키 사용이 충돌합니다."),
    RESULT_EXPIRED(HttpStatus.GONE, "GLOBAL-410-001", "처리 결과가 만료되었습니다."),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "GLOBAL-412-001", "데이터가 변경되었습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "GLOBAL-415-001",
            "지원하지 않는 요청 형식입니다."),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "GLOBAL-428-001", "변경 확인 정보가 필요합니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "GLOBAL-500-001",
            "서버에서 요청을 처리하지 못했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
