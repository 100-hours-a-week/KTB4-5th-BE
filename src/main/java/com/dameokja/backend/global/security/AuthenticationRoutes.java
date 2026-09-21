package com.dameokja.backend.global.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

public final class AuthenticationRoutes {
    public static final RequestMatcher PUBLIC = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/sessions"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/signup"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/token-renewals"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/csrf"));

    private AuthenticationRoutes() {}
}
