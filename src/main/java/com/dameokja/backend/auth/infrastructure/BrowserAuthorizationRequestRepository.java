package com.dameokja.backend.auth.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Repository;
import org.springframework.web.util.WebUtils;

@Repository
public class BrowserAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    public static final String VALIDATED = "oauth.state.validated";
    private static final long MAX_PENDING_REQUESTS = 10000;
    private static final Duration LIFETIME = Duration.ofSeconds(600);
    private static final String COOKIE = "oauthRequest";
    private static final String COOKIE_PATH = "/api/v1/auth/oauth";
    private final Cache<UUID, PendingAuthorization> requests = Caffeine.newBuilder()
            .expireAfterWrite(LIFETIME).maximumSize(MAX_PENDING_REQUESTS).build();
    private final Clock clock;
    private final boolean secure;

    public BrowserAuthorizationRequestRepository(Clock clock, @Value("${auth.cookie.secure:true}") boolean secure) {
        this.clock = clock;
        this.secure = secure;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        UUID key = browserKey(request);
        PendingAuthorization pending = key == null ? null : requests.getIfPresent(key);
        return matches(pending, request) ? pending.authorization() : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization,
            HttpServletRequest request, HttpServletResponse response) {
        if (authorization == null) {
            clear(request, response);
            return;
        }
        UUID previous = browserKey(request);
        if (previous != null) {
            requests.invalidate(previous);
        }
        UUID key = UUID.randomUUID();
        requests.put(key, new PendingAuthorization(authorization, clock.instant().plus(LIFETIME)));
        writeCookie(response, key.toString(), LIFETIME);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        UUID key = browserKey(request);
        if (key == null) {
            return null;
        }
        AtomicReference<OAuth2AuthorizationRequest> consumed = new AtomicReference<>();
        requests.asMap().computeIfPresent(key, (ignored, pending) -> {
            if (!matches(pending, request)) {
                return pending;
            }
            consumed.set(pending.authorization());
            return null;
        });
        if (consumed.get() != null) {
            request.setAttribute(VALIDATED, true);
            writeCookie(response, "", Duration.ZERO);
        }
        return consumed.get();
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        UUID key = browserKey(request);
        if (key != null) {
            requests.invalidate(key);
        }
        writeCookie(response, "", Duration.ZERO);
    }

    private boolean matches(PendingAuthorization pending, HttpServletRequest request) {
        if (pending == null || !clock.instant().isBefore(pending.expiresAt())) {
            return false;
        }
        OAuth2AuthorizationRequest authorization = pending.authorization();
        String registrationId = authorization.getAttribute("registration_id");
        String expectedPath = request.getContextPath() + "/api/v1/auth/oauth/code/" + registrationId;
        return authorization.getState().equals(request.getParameter("state")) && expectedPath.equals(request.getRequestURI());
    }

    private UUID browserKey(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, COOKIE);
        if (cookie == null || cookie.getValue() == null) {
            return null;
        }
        try {
            return UUID.fromString(cookie.getValue());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void writeCookie(HttpServletResponse response, String value, Duration lifetime) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value).httpOnly(true)
                .secure(secure).sameSite("Lax").path(COOKIE_PATH).maxAge(lifetime).build().toString());
    }

    private record PendingAuthorization(OAuth2AuthorizationRequest authorization, Instant expiresAt) {}
}
