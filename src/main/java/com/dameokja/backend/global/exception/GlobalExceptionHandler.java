package com.dameokja.backend.global.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException exception) {
        log.warn("[CustomException] code={}, message={}", exception.getExceptionCode().getCode(),
                exception.getMessage());
        return toResponse(exception.getExceptionCode(), exception.getFieldErrors());
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(bindingFieldError -> FieldError.of("BODY", "/" + bindingFieldError.getField(),
                        "FORMAT", bindingFieldError.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(exception, ErrorResponse.of(GlobalExceptionCode.BAD_REQUEST, fieldErrors),
                headers, status, request);
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

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("[MvcException] status={}", status.value(), exception);
        } else {
            log.warn("[MvcException] status={}, type={}", status.value(), exception.getClass().getSimpleName());
        }
        return super.handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Object errorResponse = body instanceof ErrorResponse ? body : mvcErrorResponse(status);
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON).body(errorResponse);
    }

    private ErrorResponse mvcErrorResponse(HttpStatusCode status) {
        return switch (HttpStatus.resolve(status.value())) {
            case BAD_REQUEST -> ErrorResponse.of(GlobalExceptionCode.BAD_REQUEST);
            case NOT_FOUND -> ErrorResponse.of(GlobalExceptionCode.NOT_FOUND);
            case METHOD_NOT_ALLOWED -> ErrorResponse.of(GlobalExceptionCode.METHOD_NOT_ALLOWED);
            case NOT_ACCEPTABLE -> ErrorResponse.of(GlobalExceptionCode.NOT_ACCEPTABLE);
            case CONTENT_TOO_LARGE -> ErrorResponse.of(GlobalExceptionCode.CONTENT_TOO_LARGE);
            case UNSUPPORTED_MEDIA_TYPE -> ErrorResponse.of(GlobalExceptionCode.UNSUPPORTED_MEDIA_TYPE);
            case INTERNAL_SERVER_ERROR -> ErrorResponse.of(GlobalExceptionCode.INTERNAL_SERVER_ERROR);
            case SERVICE_UNAVAILABLE -> ErrorResponse.of(GlobalExceptionCode.SERVICE_UNAVAILABLE);
            // 도메인 전용 오류 코드와 겹치지 않게 기본 응답은 일련번호 000을 사용한다.
            case null, default -> new ErrorResponse("GLOBAL-%d-000".formatted(status.value()),
                    "요청을 처리하지 못했습니다.", null, null);
        };
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

    private ResponseEntity<ErrorResponse> toResponse(ExceptionCode exceptionCode, List<FieldError> fieldErrors) {
        return ResponseEntity.status(exceptionCode.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(exceptionCode, fieldErrors));
    }
}
