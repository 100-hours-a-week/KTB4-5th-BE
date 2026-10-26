package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthMethodFailureWebTest extends SecurityWebTestSupport {
    @Test
    void authenticatedLoginGetReturnsMethodNotAllowed() throws Exception {
        Cookie access = new Cookie("accessToken", jwtProvider.createAccessToken(7L, UserRole.USER));

        mockMvc.perform(get("/api/v1/auth/sessions").cookie(access))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("GLOBAL-405-001"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 메서드입니다."))
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("POST", "DELETE"));
    }

    @Test
    void anonymousLoginGetKeepsAuthenticationRequirement() throws Exception {
        mockMvc.perform(get("/api/v1/auth/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
    }
}
