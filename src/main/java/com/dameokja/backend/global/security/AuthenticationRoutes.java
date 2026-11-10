package com.dameokja.backend.global.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

public final class AuthenticationRoutes {
    public static final RequestMatcher DOCUMENTATION = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/swagger-ui.html"),
            PathPatternRequestMatcher.pathPattern("/swagger-ui/**"),
            PathPatternRequestMatcher.pathPattern("/v3/api-docs/**"),
            PathPatternRequestMatcher.pathPattern("/v3/api-docs.yaml"));

    public static final RequestMatcher PUBLIC = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/sessions"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/v1/auth/sessions"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/users"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/users/oauth"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/users/nickname-validations"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/token-renewals"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/auth/csrf"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/auth/oauth/kakao"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/auth/oauth/code/kakao"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET,
                    "/api/v1/push-subscriptions/vapid-public-key"),
            new AndRequestMatcher(DOCUMENTATION, request -> HttpMethod.GET.matches(request.getMethod())));

    private AuthenticationRoutes() {}
}
