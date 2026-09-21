package com.dameokja.backend.global.security;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.user.application.UserAuthenticationService;
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
    private final JwtProvider tokenProvider;
    private final SecurityErrorHandler securityErrorHandler;
    private final UserAuthenticationService userAuthenticationService;

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

    private boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            var accessTokenCookie = WebUtils.getCookie(request, "accessToken");
            if (accessTokenCookie != null) {
                authenticate(tokenProvider.parseAccessTokenPayload(accessTokenCookie.getValue()),
                        request);
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
        var authenticatedUser = userAuthenticationService.findActive(accessTokenPayload.userId());
        var authority = new SimpleGrantedAuthority("ROLE_" + authenticatedUser.role().name());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new AuthPrincipal(accessTokenPayload.userId()), null, List.of(authority));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }
}
