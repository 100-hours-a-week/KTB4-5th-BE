package com.dameokja.backend.global.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {
        log.warn("[CustomException] code={}, message={}", e.getExceptionCode().getCode(), e.getMessage());
        return toResponse(e.getExceptionCode(), e.getFieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> FieldError.of("BODY", "/" + fe.getField(), "FORMAT", fe.getDefaultMessage()))
                .toList();
        log.warn("[Validation] fieldErrors={}", fieldErrors);
        return toResponse(GlobalExceptionCode.BAD_REQUEST, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        List<FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(cv -> FieldError.of("QUERY", cv.getPropertyPath().toString(), "FORMAT", cv.getMessage()))
                .toList();
        log.warn("[ConstraintViolation] fieldErrors={}", fieldErrors);
        return toResponse(GlobalExceptionCode.BAD_REQUEST, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[MessageNotReadable] {}", e.getMessage());
        return toResponse(GlobalExceptionCode.BAD_REQUEST, List.of());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandlerFound(NoHandlerFoundException e) {
        log.warn("[NoHandlerFound] {}", e.getMessage());
        return toResponse(GlobalExceptionCode.NOT_FOUND, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.error("[DataIntegrityViolation]", e);
        return toResponse(GlobalExceptionCode.INTERNAL_SERVER_ERROR, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
        log.error("[UnexpectedException]", e);
        return toResponse(GlobalExceptionCode.INTERNAL_SERVER_ERROR, List.of());
    }

    private ResponseEntity<ErrorResponse> toResponse(ExceptionCode exceptionCode, List<FieldError> fieldErrors) {
        HttpStatus status = exceptionCode.getStatus();
        ErrorResponse body = ErrorResponse.of(exceptionCode, fieldErrors);
        return ResponseEntity.status(status).body(body);
    }
}
