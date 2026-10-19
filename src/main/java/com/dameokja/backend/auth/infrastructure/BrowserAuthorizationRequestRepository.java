package com.dameokja.backend.auth.infrastructure;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Repository;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.ObjectMapper;

@Repository
public class BrowserAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    public static final String VALIDATED = "oauth.state.validated";
    private static final String PREFIX = "oauth:request:";
    private static final RedisScript<Long> CONSUME = RedisScript.of(
            new ClassPathResource("redis/auth/consume-authorization-request.lua"), Long.class);
    private static final Duration LIFETIME = Duration.ofSeconds(600);
    private static final String COOKIE = "oauthRequest";
    private static final String COOKIE_PATH = "/api/v1/auth/oauth";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean secure;

    public BrowserAuthorizationRequestRepository(Clock clock, @Value("${auth.cookie.secure:true}") boolean secure,
            StringRedisTemplate redis, ObjectMapper mapper) {
        this.clock = clock;
        this.secure = secure;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        UUID key = browserKey(request);
        StoredAuthorizationRequest pending = key == null ? null : read(redis.opsForValue().get(PREFIX + key));
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
            redis.delete(PREFIX + previous);
        }
        UUID key = UUID.randomUUID();
        StoredAuthorizationRequest pending = StoredAuthorizationRequest.from(authorization, clock.instant().plus(LIFETIME));
        redis.opsForValue().set(PREFIX + key, mapper.writeValueAsString(pending), LIFETIME);
        writeCookie(response, key.toString(), LIFETIME);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        UUID key = browserKey(request);
        if (key == null) {
            return null;
        }
        String stored = redis.opsForValue().get(PREFIX + key);
        StoredAuthorizationRequest pending = read(stored);
        if (!matches(pending, request) || !Long.valueOf(1).equals(redis.execute(CONSUME, List.of(PREFIX + key), stored))) {
            return null;
        }
        request.setAttribute(VALIDATED, true);
        writeCookie(response, "", Duration.ZERO);
        return pending.authorization();
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        UUID key = browserKey(request);
        if (key != null) {
            redis.delete(PREFIX + key);
        }
        writeCookie(response, "", Duration.ZERO);
    }

    private boolean matches(StoredAuthorizationRequest pending, HttpServletRequest request) {
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

    private StoredAuthorizationRequest read(String stored) {
        return stored == null ? null : mapper.readValue(stored, StoredAuthorizationRequest.class);
    }
}
