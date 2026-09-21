package com.dameokja.backend.auth.presentation;

import org.springframework.mock.web.MockHttpServletResponse;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.SecurityErrorHandler;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.user.application.AuthenticatedUser;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtFilterFailureWebTest extends SecurityWebTestSupport {
    @MockitoSpyBean SecurityErrorHandler securityErrorHandler;
    @MockitoSpyBean JwtProvider tokenValidator;

    private Cookie access() {
        return new Cookie("accessToken", jwtProvider.createAccessToken(7L, UserRole.ADMIN));
    }

    @Test
    void validTokenAuthenticatesWithoutUserLookup() throws Exception {
        when(userAuthenticationService.findActive(7L))
                .thenThrow(new CustomException(SecurityExceptionCode.USER_NOT_ACTIVE));
        mockMvc.perform(get("/api/test/me").cookie(access())).andExpect(status().isOk())
                .andExpect(content().string("7"));
        verifyNoInteractions(userAuthenticationService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authoritiesComeFromVerifiedToken() throws Exception {
        when(userAuthenticationService.findActive(7L))
                .thenReturn(new AuthenticatedUser(7L, UserRole.USER));
        mockMvc.perform(get("/api/test/role").cookie(access()))
                .andExpect(status().isOk()).andExpect(content().string("ROLE_ADMIN"));
        verifyNoInteractions(userAuthenticationService);
    }

    @Test
    void unexpectedAuthenticationFailuresReturn500WithoutLeakingDetails() throws Exception {
        doThrow(new IllegalStateException("secret validation detail"))
                .when(tokenValidator).parseAccessTokenPayload(anyString());
        MockHttpServletResponse response = mockMvc.perform(get("/api/test/me").cookie(access()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("GLOBAL-500-001")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret", "validation");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void springAuthenticationExceptionsUseEntryPoint() throws Exception {
        doThrow(new BadCredentialsException("bad credentials"))
                .when(tokenValidator).parseAccessTokenPayload(anyString());
        mockMvc.perform(get("/api/test/me").cookie(access())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void springAccessDeniedExceptionsUseDeniedHandler() throws Exception {
        doThrow(new AccessDeniedException("denied"))
                .when(tokenValidator).parseAccessTokenPayload(anyString());
        mockMvc.perform(get("/api/test/me").cookie(access())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void controllerFailuresRemainOutsideJwtExceptionHandling() throws Exception {
        mockMvc.perform(get("/api/test/fail").cookie(access()))
                .andExpect(status().isInternalServerError());
        verifyNoInteractions(securityErrorHandler);
    }
}
