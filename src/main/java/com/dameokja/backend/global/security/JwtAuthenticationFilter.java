package com.dameokja.backend.global.security;

import jakarta.servlet.http.Cookie;
import org.springframework.security.core.context.SecurityContext;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtProvider jwtProvider;
    private final SecurityErrorHandler securityErrorHandler;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return AuthenticationRoutes.PUBLIC.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (authenticateRequest(request, response)) {
            filterChain.doFilter(request, response);
        }
    }

    private String extractAccessToken(HttpServletRequest request) {
        Cookie accessTokenCookie = WebUtils.getCookie(request, "accessToken");
        return accessTokenCookie == null ? null : accessTokenCookie.getValue();
    }

    private boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            String accessToken = extractAccessToken(request);
            if (accessToken != null) {
                authenticate(jwtProvider.parseAccessTokenPayload(accessToken), request);
            }
            return true;
        } catch (CustomException exception) {
            SecurityContextHolder.clearContext();
            securityErrorHandler.write(request, response, exception.getExceptionCode());
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            securityErrorHandler.commence(request, response, exception);
        } catch (AccessDeniedException exception) {
            SecurityContextHolder.clearContext();
            securityErrorHandler.handle(request, response, exception);
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            log.error("JWT 인증 처리 실패: {}", exception.getClass().getName());
            securityErrorHandler.write(request, response,
                    GlobalExceptionCode.INTERNAL_SERVER_ERROR);
        }
        return false;
    }

    private void authenticate(AccessTokenPayload accessTokenPayload, HttpServletRequest request) {
        SimpleGrantedAuthority grantedAuthority =
                new SimpleGrantedAuthority("ROLE_" + accessTokenPayload.role().name());
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                new AuthPrincipal(accessTokenPayload.userId()), null, List.of(grantedAuthority));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }
}
