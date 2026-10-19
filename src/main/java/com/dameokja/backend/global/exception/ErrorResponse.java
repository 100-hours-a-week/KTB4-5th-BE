package com.dameokja.backend.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @Schema(description = "에러 식별 코드", requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(description = "에러 메시지", requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(description = "필드 검증 오류가 있을 때만 포함", requiredMode = Schema.RequiredMode.NOT_REQUIRED) List<FieldError> errors,
        @Schema(description = "재시도할 수 있는 오류일 때만 true로 포함", requiredMode = Schema.RequiredMode.NOT_REQUIRED) Boolean retryable
) {

    public static ErrorResponse of(ExceptionCode exceptionCode) {
        return of(exceptionCode, List.of());
    }

    public static ErrorResponse of(ExceptionCode exceptionCode, List<FieldError> fieldErrors) {
        return new ErrorResponse(
                exceptionCode.getCode(),
                exceptionCode.getMessage(),
                fieldErrors.isEmpty() ? null : fieldErrors,
                exceptionCode.isRetryable() ? Boolean.TRUE : null
        );
    }
}
