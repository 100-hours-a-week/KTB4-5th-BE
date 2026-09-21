package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.application.AuthenticatedUser;
import com.dameokja.backend.user.application.UserAuthenticationService;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final JwtProvider jwtProvider = mock(JwtProvider.class);
    private final SecurityErrorHandler securityErrorHandler = mock(SecurityErrorHandler.class);
    private final UserAuthenticationService userAuthenticationService =
            mock(UserAuthenticationService.class);
    private final JwtAuthenticationFilter jwtAuthenticationFilter =
            new JwtAuthenticationFilter(jwtProvider, securityErrorHandler,
                    userAuthenticationService);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void missingCookiePassesWithoutAuthentication() throws Exception {
        jwtAuthenticationFilter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtProvider, userAuthenticationService, securityErrorHandler);
    }

    @Test
    void currentRoleAndUserIdPopulateSecurityContext() throws Exception {
        request.setCookies(new Cookie("accessToken", "token"));
        when(jwtProvider.parseAccessTokenPayload("token"))
                .thenReturn(new AccessTokenPayload(1L, UserRole.ADMIN));
        when(userAuthenticationService.findActive(1L))
                .thenReturn(new AuthenticatedUser(1L, UserRole.USER));
        jwtAuthenticationFilter.doFilter(request, response, chain);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isEqualTo(new AuthPrincipal(1L));
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidTokenStopsChainAndClearsContext() throws Exception {
        request.setCookies(new Cookie("accessToken", "bad"));
        when(jwtProvider.parseAccessTokenPayload("bad"))
                .thenThrow(new CustomException(SecurityExceptionCode.ACCESS_TOKEN_INVALID));
        jwtAuthenticationFilter.doFilter(request, response, chain);
        verify(securityErrorHandler).write(request, response,
                SecurityExceptionCode.ACCESS_TOKEN_INVALID);
        verifyNoInteractions(chain, userAuthenticationService);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
