package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.user.application.authentication.OAuthLoginService.LoginResult;
import com.dameokja.backend.auth.application.OAuthPrincipal;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.auth.infrastructure.BrowserAuthorizationRequestRepository;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.user.application.authentication.OAuthLoginService;
import com.dameokja.backend.user.domain.UserExceptionCode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthCallbackHandlerTest {
    private final OAuthIdentity identity = new OAuthIdentity(OAuthProvider.KAKAO, "123", "member@example.com");
    private final OAuthLoginService login = mock(OAuthLoginService.class);
    private final CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    private final OAuthCallbackHandler handler = new OAuthCallbackHandler(login,
            new AuthCookies(true, Duration.ofMinutes(15), Duration.ofDays(2)), new CsrfTokenRotator(csrf),
            new SecurityErrorHandler(new ObjectMapper()), "https://front.example.com");
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Authentication authentication = new TestingAuthenticationToken(new OAuthPrincipal(identity), null);

    @AfterEach
    void clearsContext() { SecurityContextHolder.clearContext(); }

    @Test
    void existingMemberGetsServiceCookiesAndRotatedCsrf() throws Exception {
        previousCookies(true);
        when(login.login(identity)).thenReturn(new LoginResult(new TokenPair("access", "refresh", 1L), null));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://front.example.com/");
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .contains("accessToken=access", "Max-Age=900", "Path=/", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .contains("refreshToken=refresh", "Max-Age=172800", "Path=/", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(response.getCookie("registrationToken").getMaxAge()).isZero();
        assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotEqualTo("previous-csrf");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newMemberClearsOldAuthenticationAndGetsRegistrationAndCsrf(boolean hadCsrf) throws Exception {
        previousCookies(hadCsrf);
        when(login.login(identity)).thenReturn(new LoginResult(null, "pending"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://front.example.com/signup");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getCookie("accessToken").getMaxAge()).isZero();
        assertThat(response.getCookie("refreshToken").getMaxAge()).isZero();
        assertThat(response.getCookie("registrationToken").getValue()).isEqualTo("pending");
        assertThat(response.getCookie("registrationToken").getMaxAge()).isEqualTo(600);
        assertThat(response.getCookie("registrationToken").isHttpOnly()).isTrue();
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .contains("registrationToken=pending", "Max-Age=600", "Path=/", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotBlank().isNotEqualTo("previous-csrf");
        assertThat(response.getCookie("XSRF-TOKEN").isHttpOnly()).isFalse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationRequiresValidatedBrowserState(boolean validated) throws Exception {
        previousCookies(true);
        request.setAttribute(BrowserAuthorizationRequestRepository.VALIDATED, validated);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertThat(response.getRedirectedUrl()).endsWith(validated ? "OAUTH_CANCELLED" : "OAUTH_LOGIN_FAILED");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization_request_not_found", "invalid_grant", "invalid_user_info_response"})
    void oauthErrorsRedirectToLoginFailure(String code) throws Exception {
        previousCookies(true);
        handler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException(new OAuth2Error(code)));
        assertThat(response.getRedirectedUrl()).endsWith("/login?error=OAUTH_LOGIN_FAILED");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void inactiveMemberCannotReceiveAuthenticationCookies() throws Exception {
        previousCookies(true);
        when(login.login(identity)).thenThrow(new CustomException(UserExceptionCode.USER_NOT_ACTIVE));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).endsWith("OAUTH_LOGIN_FAILED");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void internalFailuresReturnJsonAndClearContext() throws Exception {
        previousCookies(true);
        when(login.login(identity)).thenThrow(new IllegalStateException("storage unavailable"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertInternalError(response);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void csrfFailureDoesNotWriteAuthenticationOrRegistrationCookies(boolean existingMember) throws Exception {
        previousCookies(true);
        CsrfTokenRotator broken = mock(CsrfTokenRotator.class);
        doThrow(new IllegalStateException("csrf unavailable")).when(broken).rotate(request, response);
        when(login.login(identity)).thenReturn(existingMember
                ? new LoginResult(new TokenPair("access", "refresh", 1L), null) : new LoginResult(null, "pending"));
        OAuthCallbackHandler failing = new OAuthCallbackHandler(login, new AuthCookies(true, Duration.ofMinutes(15), Duration.ofDays(2)),
                broken, new SecurityErrorHandler(new ObjectMapper()), "https://front.example.com");
        failing.onAuthenticationSuccess(request, response, authentication);
        assertInternalError(response);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    private void previousCookies(boolean hadCsrf) {
        List<Cookie> cookies = new ArrayList<>(List.of(new Cookie("accessToken", "previous-access"),
                new Cookie("refreshToken", "previous-refresh"), new Cookie("registrationToken", "previous-registration")));
        if (hadCsrf) { cookies.add(new Cookie("XSRF-TOKEN", "previous-csrf")); }
        request.setCookies(cookies.toArray(Cookie[]::new));
    }

    private void assertInternalError(MockHttpServletResponse result) throws Exception {
        assertThat(result.getStatus()).isEqualTo(500);
        assertThat(result.getContentAsString()).contains("GLOBAL-500-001").doesNotContain("storage unavailable");
        assertThat(result.getRedirectedUrl()).isNull();
    }
}
