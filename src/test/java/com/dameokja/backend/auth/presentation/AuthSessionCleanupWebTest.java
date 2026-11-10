package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthSessionCleanupWebTest extends SecurityWebTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "expired", "revoked", "reused"})
    void rejectedRefreshClearsAuthenticationCookies(String tokenState) throws Exception {
        TokenPair tokens = authService.login("user1", "password1");
        String token = prepareToken(tokenState, tokens.refreshToken());
        Cookie csrf = csrf();
        MockHttpServletRequestBuilder request = post("/api/v1/auth/token-renewals")
                .cookie(csrf, new Cookie("accessToken", tokens.accessToken()))
                .header("X-XSRF-TOKEN", csrf.getValue());
        if (token != null) { request.cookie(new Cookie("refreshToken", token)); }
        MockHttpServletResponse response = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("missing".equals(tokenState) ? "AUTH-401-003" : "AUTH-401-002"))
                .andReturn().getResponse();
        assertCleared(response);
    }

    @ParameterizedTest
    @EnumSource(value = UserExceptionCode.class, names = {"USER_NOT_ACTIVE", "USER_NOT_FOUND"})
    void rejectedUserClearsAuthenticationCookies(UserExceptionCode code) throws Exception {
        TokenPair tokens = authService.login("user1", "password1");
        when(userAuthenticationService.findActiveForUpdate(7L)).thenThrow(new CustomException(code));
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/token-renewals")
                        .cookie(csrf, new Cookie("accessToken", tokens.accessToken()), new Cookie("refreshToken", tokens.refreshToken()))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.code").value(code.getCode())).andReturn().getResponse();
        assertCleared(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "expired"})
    void logoutIsIdempotentWithoutAValidRefreshToken(String tokenState) throws Exception {
        TokenPair tokens = authService.login("user1", "password1");
        String token = prepareToken(tokenState, tokens.refreshToken());
        Cookie csrf = csrf();
        MockHttpServletRequestBuilder request = delete("/api/v1/auth/sessions").cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue());
        if (token != null) { request.cookie(new Cookie("refreshToken", token)); }
        MockHttpServletResponse response = mockMvc.perform(request)
                .andExpect(status().isOk()).andReturn().getResponse();
        assertCleared(response);
        assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotEqualTo(csrf.getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void csrfRejectionDoesNotClearAuthenticationCookies(String method) throws Exception {
        TokenPair tokens = authService.login("user1", "password1");
        Cookie csrf = csrf();
        String path = "POST".equals(method) ? "/api/v1/auth/token-renewals" : "/api/v1/auth/sessions";
        MockHttpServletResponse response = mockMvc.perform(request(HttpMethod.valueOf(method), path)
                        .cookie(csrf, new Cookie("refreshToken", tokens.refreshToken()))
                        .header("X-XSRF-TOKEN", "mismatch"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001")).andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
        authService.refresh(tokens.refreshToken());
    }

    private String prepareToken(String tokenState, String token) {
        if ("expired".equals(tokenState)) { clock.advance(Duration.ofDays(3)); }
        if ("revoked".equals(tokenState)) { authService.logout(token); }
        if ("reused".equals(tokenState)) { authService.refresh(token); }
        if ("missing".equals(tokenState)) { return null; }
        return "invalid".equals(tokenState) ? "invalid" : token;
    }

    private void assertCleared(MockHttpServletResponse response) {
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        for (String name : List.of("accessToken", "refreshToken")) {
            Cookie cookie = response.getCookie(name);
            assertThat(cookie).isNotNull();
            assertThat(cookie.getValue()).isEmpty();
            assertThat(cookie.getMaxAge()).isZero();
            assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getSecure()).isTrue();
            assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        }
    }
}
