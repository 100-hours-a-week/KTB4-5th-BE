package com.dameokja.backend.user.presentation;

import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.application.SignupResult;
import com.dameokja.backend.user.domain.UserExceptionCode;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserSignupWebTest extends UserSecurityWebTestSupport {
    private static final String BODY = """
            {"loginId":"user1","password":"password1","nickname":"별명"}
            """;

    @Test
    void signupReturnsCreatedWritesAuthCookiesAndRotatesCsrf() throws Exception {
        when(userSignupService.signup("user1", "password1", "별명")).thenReturn(new SignupResult(
                new TokenPair("access-token", "refresh-token", 7L), List.of(1L)));
        Cookie before = csrf();
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/users").cookie(before)
                        .header("X-XSRF-TOKEN", before.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("USER-201-001"))
                .andExpect(jsonPath("$.message").value("회원가입 성공"))
                .andExpect(jsonPath("$.data.activeRefrigeratorIds[0]").value("1"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken").getValue()).isEqualTo("access-token");
        assertThat(response.getCookie("accessToken").getMaxAge()).isEqualTo(900);
        assertThat(response.getCookie("refreshToken").getValue()).isEqualTo("refresh-token");
        assertThat(response.getCookie("refreshToken").getMaxAge()).isEqualTo(172800);
        assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotEqualTo(before.getValue());
    }

    @Test
    void signupWithoutCsrfCookieIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        verifyNoInteractions(userSignupService);
    }

    @Test
    void signupWithMismatchedCsrfHeaderIsForbidden() throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users").cookie(csrf).header("X-XSRF-TOKEN", "mismatch")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        verifyNoInteractions(userSignupService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"loginId\":\"a\",\"password\":\"password1\"}",
            "{\"loginId\":\"한글아이디\",\"password\":\"password1\"}",
            "{\"loginId\":\"user1\",\"password\":\"short1\"}",
            "{\"loginId\":\"user1\",\"password\":\"12345678\"}"
    })
    void malformedLoginIdOrPasswordUsesCommonErrorBody(String body) throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-001"));
        verifyNoInteractions(userSignupService);
    }

    @Test
    void requiredFieldErrorUsesSpecificUserCode() throws Exception {
        when(userSignupService.signup(any(), any(), any()))
                .thenThrow(new CustomException(UserExceptionCode.NICKNAME_REQUIRED));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER-400-001"));
    }

    @Test
    void duplicateNicknameUsesConflictCode() throws Exception {
        when(userSignupService.signup(any(), any(), any()))
                .thenThrow(new CustomException(UserExceptionCode.NICKNAME_DUPLICATE));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER-409-001"));
    }

    @Test
    void prohibitedLoginIdUsesUnprocessableEntityCode() throws Exception {
        when(userSignupService.signup(any(), any(), any()))
                .thenThrow(new CustomException(UserExceptionCode.LOGIN_ID_PROHIBITED));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/users").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("USER-422-002"));
    }

    @Test
    void unexpectedFailureReturnsServerErrorWithoutCookies() throws Exception {
        when(userSignupService.signup(any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/users").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
    }
}
