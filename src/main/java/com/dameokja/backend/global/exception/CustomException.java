package com.dameokja.backend.global.exception;

import lombok.Getter;

import java.util.List;

@Getter
public class CustomException extends RuntimeException {

    private final ExceptionCode exceptionCode;
    private final List<FieldError> fieldErrors;

    public CustomException(ExceptionCode exceptionCode) {
        this(exceptionCode, List.of());
    }

    public CustomException(ExceptionCode exceptionCode, List<FieldError> fieldErrors) {
        super(exceptionCode.getMessage());
        this.exceptionCode = exceptionCode;
        this.fieldErrors = fieldErrors;
    }
}
