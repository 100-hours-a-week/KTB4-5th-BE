package com.dameokja.backend.global.exception;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class MvcFrameworkExceptionResponseTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    static Stream<Arguments> frameworkErrors() {
        return Stream.of(
                Arguments.of(new MaxUploadSizeExceededException(10), 413, "GLOBAL-413-001"),
                Arguments.of(new AsyncRequestTimeoutException(), 503, "GLOBAL-503-001"),
                Arguments.of(new HttpMessageNotWritableException("private server detail"), 500, "GLOBAL-500-001"),
                Arguments.of(new ConversionNotSupportedException("private value", String.class,
                        new IllegalStateException("private cause")), 500, "GLOBAL-500-001"),
                Arguments.of(statusException(429), 429, "GLOBAL-429-000"),
                Arguments.of(statusException(499), 499, "GLOBAL-499-000"),
                Arguments.of(statusException(409), 409, "GLOBAL-409-000"));
    }

    @ParameterizedTest
    @MethodSource("frameworkErrors")
    void frameworkErrorsPreserveStatusAndSafeBody(Exception exception, int status, String code) throws Exception {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
        ResponseEntity<Object> response = handler.handleException(exception, request);
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isInstanceOfSatisfying(ErrorResponse.class, body -> {
            assertThat(body.code()).isEqualTo(code);
            assertThat(body.message()).doesNotContain("private");
            assertThat(body.errors()).isNull();
        });
        if (exception instanceof ErrorResponseException) {
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        }
    }

    @Test
    void committedResponsesAreNotWrittenAgain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest(), response);
        assertThat(handler.handleException(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), request)).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void unusableAsyncResponsesAreNotWrittenAgain() throws Exception {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(handler.handleException(new AsyncRequestNotUsableException("connection closed"), request)).isNull();
    }

    private static ErrorResponseException statusException(int status) {
        ErrorResponseException exception = new ErrorResponseException(HttpStatusCode.valueOf(status),
                ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), "private server detail"), null);
        exception.getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
        return exception;
    }
}
