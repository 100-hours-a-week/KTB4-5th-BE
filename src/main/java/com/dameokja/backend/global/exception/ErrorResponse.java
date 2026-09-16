package com.dameokja.backend.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> errors,
        Boolean retryable
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
