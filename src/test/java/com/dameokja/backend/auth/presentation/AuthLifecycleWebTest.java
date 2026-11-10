package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.security.RefreshTokenPayload;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthLifecycleWebTest extends SecurityWebTestSupport {
    private MockHttpServletResponse login(Cookie csrf) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sessions").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"user1\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    @Test
    void loginWritesScopedHttpOnlyCookiesAndRotatesCsrf() throws Exception {
        Cookie before = csrf();
        MockHttpServletResponse response = login(before);
        Cookie access = response.getCookie("accessToken");
        Cookie refresh = response.getCookie("refreshToken");
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getSecure()).isTrue();
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(access.getMaxAge()).isEqualTo(900);
        assertThat(refresh.getPath()).isEqualTo("/");
        assertThat(refresh.getMaxAge()).isEqualTo(172800);
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getDomain()).isNull();
        assertThat(access.getAttribute("SameSite")).isEqualTo("Lax");
        Cookie after = response.getCookie("XSRF-TOKEN");
        assertThat(after.getValue()).isNotEqualTo(before.getValue());
        assertThat(after.isHttpOnly()).isFalse();
        assertThat(response.getContentAsString()).contains("AUTH-200-001", "activeRefrigeratorIds");
    }

    @Test
    void refreshBypassesExpiredAccessAndPreservesCsrfWhileExtendingExpiry() throws Exception {
        MockHttpServletResponse login = login(csrf());
        Cookie csrf = login.getCookie("XSRF-TOKEN");
        Cookie refresh = login.getCookie("refreshToken");
        RefreshTokenPayload oldPayload = jwtProvider.parseRefreshTokenPayload(refresh.getValue());
        clock.advance(Duration.ofMinutes(16));
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/token-renewals")
                        .cookie(csrf, refresh, login.getCookie("accessToken"))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andReturn().getResponse();
        RefreshTokenPayload payload =
                jwtProvider.parseRefreshTokenPayload(response.getCookie("refreshToken").getValue());
        assertThat(payload.sid()).isEqualTo(oldPayload.sid());
        assertThat(payload.jti()).isNotEqualTo(oldPayload.jti());
        assertThat(payload.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(2)));
        assertThat(response.getCookie("XSRF-TOKEN")).isNull();
    }

    @Test
    void replayRevokesTheNewRefreshToo() throws Exception {
        MockHttpServletResponse login = login(csrf());
        Cookie csrf = login.getCookie("XSRF-TOKEN");
        Cookie original = login.getCookie("refreshToken");
        MockHttpServletResponse renewed = mockMvc.perform(post("/api/v1/auth/token-renewals")
                        .cookie(csrf, original)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andReturn().getResponse();
        mockMvc.perform(post("/api/v1/auth/token-renewals").cookie(csrf, original)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-002"));
        mockMvc.perform(post("/api/v1/auth/token-renewals")
                        .cookie(csrf, renewed.getCookie("refreshToken"))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-002"));
    }

    @Test
    void logoutRevokesSessionClearsCookiePathsAndRotatesCsrf() throws Exception {
        MockHttpServletResponse login = login(csrf());
        Cookie csrf = login.getCookie("XSRF-TOKEN");
        Cookie refresh = login.getCookie("refreshToken");
        MockHttpServletResponse response = mockMvc.perform(delete("/api/v1/auth/sessions")
                        .cookie(csrf, refresh, login.getCookie("accessToken"))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getCookie("accessToken").getMaxAge()).isZero();
        assertThat(response.getCookie("accessToken").getPath()).isEqualTo("/");
        assertThat(response.getCookie("refreshToken").getMaxAge()).isZero();
        assertThat(response.getCookie("refreshToken").getPath()).isEqualTo("/");
        assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotEqualTo(csrf.getValue());
        mockMvc.perform(post("/api/v1/auth/token-renewals").cookie(csrf, refresh)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "invalid"})
    void logoutRevokesRefreshWithoutAValidAccessToken(String accessState) throws Exception {
        MockHttpServletResponse login = login(csrf());
        Cookie csrf = login.getCookie("XSRF-TOKEN");
        Cookie refresh = login.getCookie("refreshToken");
        if ("expired".equals(accessState)) { clock.advance(Duration.ofMinutes(16)); }
        MockHttpServletRequestBuilder request = delete("/api/v1/auth/sessions").cookie(csrf, refresh)
                .header("X-XSRF-TOKEN", csrf.getValue());
        if (!"missing".equals(accessState)) {
            request.cookie("invalid".equals(accessState) ? new Cookie("accessToken", "invalid") : login.getCookie("accessToken"));
        }
        MockHttpServletResponse response = mockMvc.perform(request)
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getCookie("accessToken").getMaxAge()).isZero();
        assertThat(response.getCookie("refreshToken").getMaxAge()).isZero();
        Cookie rotated = response.getCookie("XSRF-TOKEN");
        assertThat(rotated.getValue()).isNotEqualTo(csrf.getValue());
        mockMvc.perform(post("/api/v1/auth/token-renewals").cookie(rotated, refresh)
                        .header("X-XSRF-TOKEN", rotated.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-002"));
    }

    @Test
    void authMutationsRequireMatchingCsrf() throws Exception {
        for (String path : new String[]{"/api/v1/auth/sessions", "/api/v1/users",
                "/api/v1/auth/token-renewals"}) {
            mockMvc.perform(post(path)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        }
        mockMvc.perform(delete("/api/v1/auth/sessions")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        mockMvc.perform(post("/api/v1/auth/token-renewals").cookie(csrf())
                        .header("X-XSRF-TOKEN", "mismatch"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userAuthenticationService);
    }

    @Test
    void rejectedLoginDoesNotIssueAuthenticationCookies() throws Exception {
        when(userAuthenticationService.authenticate("user1", "password1"))
                .thenThrow(new CustomException(AuthExceptionCode.INVALID_CREDENTIALS));
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/sessions")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"user1\",\"password\":\"password1\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
        assertThat(response.getCookie("XSRF-TOKEN")).isNull();
    }
}
