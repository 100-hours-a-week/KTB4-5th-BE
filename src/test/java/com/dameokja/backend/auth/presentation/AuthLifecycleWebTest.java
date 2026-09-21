package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

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
