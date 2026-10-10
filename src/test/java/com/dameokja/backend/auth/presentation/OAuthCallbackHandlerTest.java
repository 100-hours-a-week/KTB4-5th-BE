package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.OAuthPrincipal;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.auth.infrastructure.BrowserAuthorizationRequestRepository;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.user.application.OAuthLoginService;
import com.dameokja.backend.user.application.OAuthLoginService.LoginResult;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.time.Duration;
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
        when(login.login(identity)).thenReturn(new LoginResult(new TokenPair("access", "refresh", 1L), null));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://front.example.com/");
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .contains("accessToken=access", "Max-Age=900", "Path=/", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie)
                .contains("refreshToken=refresh", "Max-Age=172800", "Path=/", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(cookie -> cookie.startsWith("XSRF-TOKEN="));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void newMemberGetsOnlyPendingRegistrationCookie() throws Exception {
        when(login.login(identity)).thenReturn(new LoginResult(null, "pending"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("https://front.example.com/signup");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).singleElement().asString()
                .contains("registrationToken=pending", "Max-Age=600", "Path=/", "Secure", "HttpOnly", "SameSite=Lax");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationRequiresValidatedBrowserState(boolean validated) throws Exception {
        request.setAttribute(BrowserAuthorizationRequestRepository.VALIDATED, validated);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException(new OAuth2Error("access_denied")));
        assertThat(response.getRedirectedUrl()).endsWith(validated ? "OAUTH_CANCELLED" : "OAUTH_LOGIN_FAILED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorization_request_not_found", "invalid_grant", "invalid_user_info_response"})
    void oauthErrorsRedirectToLoginFailure(String code) throws Exception {
        handler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException(new OAuth2Error(code)));
        assertThat(response.getRedirectedUrl()).endsWith("/login?error=OAUTH_LOGIN_FAILED");
    }

    @Test
    void inactiveMemberCannotReceiveAuthenticationCookies() throws Exception {
        when(login.login(identity)).thenThrow(new CustomException(UserExceptionCode.USER_NOT_ACTIVE));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).endsWith("OAUTH_LOGIN_FAILED");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void internalFailuresReturnJsonAndClearContext() throws Exception {
        when(login.login(identity)).thenThrow(new IllegalStateException("storage unavailable"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        handler.onAuthenticationSuccess(request, response, authentication);
        assertInternalError(response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void csrfFailureDoesNotWriteAuthenticationCookies() throws Exception {
        CsrfTokenRotator broken = mock(CsrfTokenRotator.class);
        doThrow(new IllegalStateException("csrf unavailable")).when(broken).rotate(request, response);
        when(login.login(identity)).thenReturn(new LoginResult(new TokenPair("access", "refresh", 1L), null));
        OAuthCallbackHandler failing = new OAuthCallbackHandler(login, new AuthCookies(true, Duration.ofMinutes(15), Duration.ofDays(2)),
                broken, new SecurityErrorHandler(new ObjectMapper()), "https://front.example.com");
        failing.onAuthenticationSuccess(request, response, authentication);
        assertInternalError(response);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    private void assertInternalError(MockHttpServletResponse result) throws Exception {
        assertThat(result.getStatus()).isEqualTo(500);
        assertThat(result.getContentAsString()).contains("GLOBAL-500-001").doesNotContain("storage unavailable");
        assertThat(result.getRedirectedUrl()).isNull();
    }
}
