package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.TokenPair;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    private static final String ACCESS_PATH = "/";
    private static final String REFRESH_PATH = "/";
    private final boolean secure;
    private final Duration accessLifetime;
    private final Duration refreshLifetime;

    public AuthCookies(@Value("${auth.cookie.secure:true}") boolean secure,
            @Value("${jwt.access-token-expiration}") Duration accessLifetime,
            @Value("${jwt.refresh-token-expiration}") Duration refreshLifetime) {
        this.secure = secure;
        this.accessLifetime = accessLifetime;
        this.refreshLifetime = refreshLifetime;
    }

    public void write(HttpServletResponse response, TokenPair tokenPair) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie("accessToken", tokenPair.accessToken(), ACCESS_PATH, accessLifetime));
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie("refreshToken", tokenPair.refreshToken(), REFRESH_PATH, refreshLifetime));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie("accessToken", "", ACCESS_PATH, Duration.ZERO));
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie("refreshToken", "", REFRESH_PATH, Duration.ZERO));
    }

    private String cookie(String name, String value, String path, Duration lifetime) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure)
                .sameSite("Lax").path(path).maxAge(lifetime).build().toString();
    }
}
