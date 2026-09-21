package com.dameokja.backend.auth.presentation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CsrfTokenRotator {
    private final CookieCsrfTokenRepository csrfTokenRepository;

    public void rotate(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request,
                response);
    }
}
