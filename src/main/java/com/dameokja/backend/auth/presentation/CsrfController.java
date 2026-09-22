package com.dameokja.backend.auth.presentation;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController implements CsrfApi {
    @Override
    @GetMapping("/api/v1/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
    }
}
