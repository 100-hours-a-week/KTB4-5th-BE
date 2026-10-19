package com.dameokja.backend.user.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

class UserWithdrawalWebTest extends UserSecurityWebTestSupport {
    private static final String WITHDRAWAL_PATH = "/api/v1/users/me";
    @Autowired JwtProvider jwt;

    @Test
    void withdrawsAuthenticatedUserAndClearsCookiesWithNoContent() throws Exception {
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(delete(WITHDRAWAL_PATH)
                        .cookie(access(), csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .param("userId", "99"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andReturn().getResponse();
        verify(userWithdrawalService).withdraw(1L);
        verifyNoMoreInteractions(userWithdrawalService);
        for (String name : new String[]{"accessToken", "refreshToken"}) {
            Cookie cleared = response.getCookie(name);
            assertThat(cleared).isNotNull();
            assertThat(cleared.getMaxAge()).isZero();
            assertThat(cleared.getPath()).isEqualTo("/");
            assertThat(cleared.isHttpOnly()).isTrue();
            assertThat(cleared.getSecure()).isTrue();
        }
        assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotEqualTo(csrf.getValue());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void rejectsMissingAuthenticationEvenWithValidCsrf() throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(delete(WITHDRAWAL_PATH).cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
        verifyNoInteractions(userWithdrawalService);
    }

    @Test
    void rejectsInvalidAccessToken() throws Exception {
        Cookie csrf = csrf();
        mockMvc.perform(delete(WITHDRAWAL_PATH).cookie(new Cookie("accessToken", "invalid"), csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userWithdrawalService);
    }

    @Test
    void rejectsMissingCsrf() throws Exception {
        mockMvc.perform(delete(WITHDRAWAL_PATH).cookie(access()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        verifyNoInteractions(userWithdrawalService);
    }

    @Test
    void rejectsMismatchedCsrf() throws Exception {
        mockMvc.perform(delete(WITHDRAWAL_PATH).cookie(access(), csrf())
                        .header("X-XSRF-TOKEN", "mismatch"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON-403-CSRF-001"));
        verifyNoInteractions(userWithdrawalService);
    }

    @Test
    void missingUserReturnsNotFoundWithoutClearingAuthenticationCookies() throws Exception {
        doThrow(new CustomException(UserExceptionCode.USER_NOT_FOUND)).when(userWithdrawalService).withdraw(1L);
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(delete(WITHDRAWAL_PATH)
                        .cookie(access(), csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER-404-001"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
        verify(userWithdrawalService).withdraw(1L);
    }

    @Test
    void failedWithdrawalReturnsServerErrorWithoutClearingAuthenticationCookies() throws Exception {
        doThrow(new CustomException(GlobalExceptionCode.INTERNAL_SERVER_ERROR)).when(userWithdrawalService).withdraw(1L);
        Cookie csrf = csrf();
        MockHttpServletResponse response = mockMvc.perform(delete(WITHDRAWAL_PATH)
                        .cookie(access(), csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001"))
                .andReturn().getResponse();
        assertThat(response.getCookie("accessToken")).isNull();
        assertThat(response.getCookie("refreshToken")).isNull();
        verify(userWithdrawalService).withdraw(1L);
    }

    private Cookie access() { return new Cookie("accessToken", jwt.createAccessToken(1L, UserRole.USER)); }
}
