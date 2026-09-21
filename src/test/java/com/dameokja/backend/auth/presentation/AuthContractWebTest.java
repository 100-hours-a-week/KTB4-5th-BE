package com.dameokja.backend.auth.presentation;

import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthContractWebTest extends SecurityWebTestSupport {
    @Autowired RefrigeratorAccessService refrigeratorAccessService;

    @Test
    void loginNormalizesIdAndReturnsActiveRefrigeratorsInCommonResponse() throws Exception {
        when(refrigeratorAccessService.findActiveRefrigeratorIds(7L)).thenReturn(List.of(1L, 2L));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/auth/sessions").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":" user 1 ","password":"password1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTH-200-001"))
                .andExpect(jsonPath("$.message").value("로그인 성공"))
                .andExpect(jsonPath("$.data.activeRefrigeratorIds[0]").value("1"))
                .andExpect(jsonPath("$.data.activeRefrigeratorIds[1]").value("2"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"loginId\":\"a\",\"password\":\"password1\"}",
            "{\"loginId\":\"abcdefghijk\",\"password\":\"password1\"}",
            "{\"loginId\":\"한글\",\"password\":\"password1\"}",
            "{\"loginId\":\"user1\",\"password\":\"password\"}",
            "{\"loginId\":\"user1\",\"password\":\"12345678\"}",
            "{\"loginId\":\"user1\",\"password\":\"abc123\"}"
    })
    void invalidCredentialsFormatUsesCommonErrorBody(String body) throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/auth/sessions").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknownLoginIdUsesNotFoundCode() throws Exception {
        when(userAuthenticationService.authenticate("user1", "password1"))
                .thenThrow(new CustomException(AuthExceptionCode.LOGIN_ID_NOT_FOUND));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/auth/sessions").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"user1\",\"password\":\"password1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTH-404-001"));
    }

}
