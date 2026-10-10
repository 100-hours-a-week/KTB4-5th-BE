package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.OAuthPrincipal;
import com.dameokja.backend.auth.infrastructure.BrowserAuthorizationRequestRepository;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.user.application.OAuthLoginService;
import com.dameokja.backend.user.application.OAuthLoginService.LoginResult;
import com.dameokja.backend.user.domain.UserExceptionCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OAuthCallbackHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    private final OAuthLoginService login;
    private final AuthCookies cookies;
    private final CsrfTokenRotator csrf;
    private final SecurityErrorHandler errors;
    private final String frontend;

    public OAuthCallbackHandler(OAuthLoginService login, AuthCookies cookies, CsrfTokenRotator csrf,
            SecurityErrorHandler errors, @Value("${oauth.frontend-base-url}") String frontend) {
        this.login = login;
        this.cookies = cookies;
        this.csrf = csrf;
        this.errors = errors;
        this.frontend = frontend.replaceAll("/+$", "");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            completeLogin(request, response, (OAuthPrincipal) authentication.getPrincipal());
        } catch (RuntimeException exception) {
            if (exception instanceof CustomException custom && custom.getExceptionCode() == UserExceptionCode.USER_NOT_ACTIVE) {
                redirect(response, "/login?error=OAUTH_LOGIN_FAILED");
            } else {
                internalError(response, exception);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        try {
            boolean cancelled = Boolean.TRUE.equals(request.getAttribute(BrowserAuthorizationRequestRepository.VALIDATED))
                    && exception instanceof OAuth2AuthenticationException oauth
                    && "access_denied".equals(oauth.getError().getErrorCode());
            redirect(response, "/login?error=" + (cancelled ? "OAUTH_CANCELLED" : "OAUTH_LOGIN_FAILED"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void completeLogin(HttpServletRequest request, HttpServletResponse response, OAuthPrincipal principal) throws IOException {
        LoginResult result = login.login(principal.identity());
        if (result.tokens() == null) {
            cookies.writeRegistration(response, result.registrationToken());
            redirect(response, "/signup");
            return;
        }
        csrf.rotate(request, response);
        cookies.write(response, result.tokens());
        redirect(response, "/");
    }

    private void redirect(HttpServletResponse response, String path) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.sendRedirect(frontend + path);
    }

    private void internalError(HttpServletResponse response, RuntimeException exception) throws IOException {
        log.error("OAuth 로그인 후처리 실패", exception);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        errors.write(response, GlobalExceptionCode.INTERNAL_SERVER_ERROR);
    }
}
