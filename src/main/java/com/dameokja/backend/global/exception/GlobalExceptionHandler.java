package com.dameokja.backend.global.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException exception) {
        log.warn("[CustomException] code={}, message={}", exception.getExceptionCode().getCode(),
                exception.getMessage());
        return toResponse(exception.getExceptionCode(), exception.getFieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(bindingFieldError -> FieldError.of("BODY", "/" + bindingFieldError.getField(),
                        "FORMAT", bindingFieldError.getDefaultMessage()))
                .toList();
        log.warn("[Validation] fieldErrors={}", fieldErrors);
        return toResponse(GlobalExceptionCode.BAD_REQUEST, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception) {
        List<FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(constraintViolation -> FieldError.of("QUERY",
                        constraintViolation.getPropertyPath().toString(),
                        "FORMAT", constraintViolation.getMessage()))
                .toList();
        log.warn("[ConstraintViolation] fieldErrors={}", fieldErrors);
        return toResponse(GlobalExceptionCode.BAD_REQUEST, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException exception) {
        log.warn("[MessageNotReadable] {}", exception.getMessage());
        return toResponse(GlobalExceptionCode.BAD_REQUEST, List.of());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandlerFound(NoHandlerFoundException exception) {
        log.warn("[NoHandlerFound] {}", exception.getMessage());
        return toResponse(GlobalExceptionCode.NOT_FOUND, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {
        log.error("[DataIntegrityViolation]", exception);
        return toResponse(GlobalExceptionCode.INTERNAL_SERVER_ERROR, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("[UnexpectedException]", exception);
        return toResponse(GlobalExceptionCode.INTERNAL_SERVER_ERROR, List.of());
    }

    private ResponseEntity<ErrorResponse> toResponse(ExceptionCode exceptionCode,
            List<FieldError> fieldErrors) {
        HttpStatus httpStatus = exceptionCode.getStatus();
        ErrorResponse errorResponse = ErrorResponse.of(exceptionCode, fieldErrors);
        return ResponseEntity.status(httpStatus).body(errorResponse);
    }
}
