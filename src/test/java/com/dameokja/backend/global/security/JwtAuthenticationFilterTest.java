package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final JwtProvider jwtProvider = mock(JwtProvider.class);
    private final SecurityErrorHandler securityErrorHandler = mock(SecurityErrorHandler.class);
    private final JwtAuthenticationFilter jwtAuthenticationFilter =
            new JwtAuthenticationFilter(jwtProvider, securityErrorHandler);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain filterChain = mock(FilterChain.class);

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void missingCookiePassesWithoutAuthentication() throws Exception {
        request.setCookies(new Cookie("refreshToken", "refresh"));
        jwtAuthenticationFilter.doFilter(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtProvider, securityErrorHandler);
    }

    @Test
    void tokenRoleAndUserIdPopulateSecurityContext() throws Exception {
        request.setCookies(new Cookie("accessToken", "token"));
        when(jwtProvider.parseAccessTokenPayload("token"))
                .thenReturn(new AccessTokenPayload(1L, UserRole.ADMIN));
        jwtAuthenticationFilter.doFilter(request, response, filterChain);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo(new AuthPrincipal(1L));
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_ADMIN");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void emptyAccessCookieIsValidatedAndRejected() throws Exception {
        request.setCookies(new Cookie("accessToken", ""));
        when(jwtProvider.parseAccessTokenPayload(""))
                .thenThrow(new CustomException(SecurityExceptionCode.ACCESS_TOKEN_INVALID));
        jwtAuthenticationFilter.doFilter(request, response, filterChain);
        verify(jwtProvider).parseAccessTokenPayload("");
        verify(securityErrorHandler).write(response, SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        verifyNoInteractions(filterChain);
    }

    @Test
    void invalidTokenStopsChainAndClearsContext() throws Exception {
        request.setCookies(new Cookie("accessToken", "bad"));
        when(jwtProvider.parseAccessTokenPayload("bad"))
                .thenThrow(new CustomException(SecurityExceptionCode.ACCESS_TOKEN_INVALID));
        jwtAuthenticationFilter.doFilter(request, response, filterChain);
        verify(securityErrorHandler).write(response, SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        verifyNoInteractions(filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
