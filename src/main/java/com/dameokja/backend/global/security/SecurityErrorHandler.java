package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.ErrorResponse;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.exception.ExceptionCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        write(request, response, SecurityExceptionCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        write(request, response, exception instanceof CsrfException
                ? SecurityExceptionCode.CSRF_TOKEN_INVALID : SecurityExceptionCode.ACCESS_DENIED);
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
            ExceptionCode exceptionCode)
            throws IOException {
        if ("DELETE".equals(request.getMethod())
                && "/api/v1/auth/sessions".equals(request.getRequestURI())) {
            if (exceptionCode == SecurityExceptionCode.AUTHENTICATION_REQUIRED) {
                exceptionCode = AuthExceptionCode.LOGOUT_REQUIRED;
            } else if (exceptionCode.getStatus().is5xxServerError()) {
                exceptionCode = AuthExceptionCode.LOGOUT_FAILED;
            }
        }
        response.setStatus(exceptionCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(exceptionCode));
    }
}
