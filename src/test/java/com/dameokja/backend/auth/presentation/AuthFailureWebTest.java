package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.infrastructure.RefreshSessionStore;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFailureWebTest extends SecurityWebTestSupport {
    @MockitoBean
    RefreshSessionStore refreshSessionStore;

    @Test
    void failedSessionCreationNeverWritesAuthenticationCookies() throws Exception {
        doThrow(new IllegalStateException("store unavailable"))
                .when(refreshSessionStore).create(any());
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/sessions")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"user1\",\"password\":\"password1\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
        assertThat(response.getCookie("XSRF-TOKEN")).isNull();
    }

    @Test
    void failedRotationNeverWritesAuthenticationCookies() throws Exception {
        doThrow(new IllegalStateException("unavailable"))
                .when(refreshSessionStore).rotate(any(), any());
        Cookie csrf = csrf();
        String token = jwtProvider.createRefreshToken(7L, UUID.randomUUID());
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/token-renewals")
                        .cookie(csrf, new Cookie("refreshToken", token))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
    }

    @Test
    void logoutStorageFailureReturnsServerErrorWithoutClearingCookies() throws Exception {
        doThrow(new IllegalStateException("unavailable")).when(refreshSessionStore).revoke(any());
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(delete("/api/v1/auth/sessions")
                        .cookie(csrf, new Cookie("accessToken",
                                jwtProvider.createAccessToken(7L, UserRole.USER)),
                                new Cookie("refreshToken",
                                        jwtProvider.createRefreshToken(7L, UUID.randomUUID())))
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
    }

}
