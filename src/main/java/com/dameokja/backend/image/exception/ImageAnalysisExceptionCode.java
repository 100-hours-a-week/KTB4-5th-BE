package com.dameokja.backend.image.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ImageAnalysisExceptionCode implements ExceptionCode {
    INVALID_IMAGE(HttpStatus.UNPROCESSABLE_CONTENT, "IMAGE-422-001", "이미지 제한을 위반했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
