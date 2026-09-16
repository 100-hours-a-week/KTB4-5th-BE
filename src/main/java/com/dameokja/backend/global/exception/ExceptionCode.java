package com.dameokja.backend.global.exception;

import org.springframework.http.HttpStatus;

public interface ExceptionCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();

    default boolean isRetryable() {
        return false;
    }
}
