package com.dameokja.backend.auth.infrastructure;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserAuthorizationRequestRepositoryTest {
    private final Clock clock = mock(Clock.class);
    private BrowserAuthorizationRequestRepository repository;
    private Cookie browser;
    private OAuth2AuthorizationRequest authorization;
    private final Instant start = Instant.parse("2026-10-09T00:00:00Z");

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(start);
        repository = new BrowserAuthorizationRequestRepository(clock, true);
        authorization = OAuth2AuthorizationRequest.authorizationCode().clientId("test-client")
                .authorizationUri("https://provider.example.com/authorize")
                .redirectUri("https://service.example.com/api/v1/auth/oauth/code/kakao")
                .state("test-state").attributes(attributes -> attributes.put("registration_id", "kakao")).build();
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization, new MockHttpServletRequest(), response);
        browser = response.getCookie("oauthRequest");
        assertThat(browser).isNotNull();
        assertThat(browser.isHttpOnly()).isTrue();
        assertThat(browser.getSecure()).isTrue();
        assertThat(browser.getMaxAge()).isEqualTo(600);
        assertThat(browser.getPath()).isEqualTo("/api/v1/auth/oauth");
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void consumesOnceAndMarksValidatedCallback() {
        MockHttpServletRequest request = callback(browser, "kakao", "test-state");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(repository.loadAuthorizationRequest(request)).isSameAs(authorization);
        assertThat(repository.removeAuthorizationRequest(request, response)).isSameAs(authorization);
        assertThat(request.getAttribute(BrowserAuthorizationRequestRepository.VALIDATED)).isEqualTo(true);
        assertThat(response.getCookie("oauthRequest").getMaxAge()).isZero();
        assertThat(repository.removeAuthorizationRequest(request, response)).isNull();
    }

    @Test
    void rejectsMissingBrowserWrongStateAndWrongProviderWithoutConsumingValidRequest() {
        assertThat(repository.removeAuthorizationRequest(callback(null, "kakao", "test-state"),
                new MockHttpServletResponse())).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(browser, "kakao", "wrong"),
                new MockHttpServletResponse())).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(browser, "google", "test-state"),
                new MockHttpServletResponse())).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(new Cookie("oauthRequest", "invalid"), "kakao", "test-state"),
                new MockHttpServletResponse())).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(browser, "kakao", "test-state"),
                new MockHttpServletResponse())).isSameAs(authorization);
    }

    @Test
    void expiresExactlyAtTenMinutes() {
        when(clock.instant()).thenReturn(start.plusSeconds(600));
        assertThat(repository.removeAuthorizationRequest(callback(browser, "kakao", "test-state"),
                new MockHttpServletResponse())).isNull();
    }

    @Test
    void replacesPreviousBrowserRequest() {
        MockHttpServletRequest startRequest = new MockHttpServletRequest();
        startRequest.setCookies(browser);
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorization, startRequest, response);
        Cookie replacement = response.getCookie("oauthRequest");
        assertThat(replacement.getValue()).isNotEqualTo(browser.getValue());
        assertThat(repository.loadAuthorizationRequest(callback(browser, "kakao", "test-state"))).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(replacement, "kakao", "test-state"),
                new MockHttpServletResponse())).isSameAs(authorization);
    }

    @Test
    void rejectsUnknownBrowserAndMissingStateWithoutValidatingCallback() {
        MockHttpServletRequest unknownBrowser = callback(new Cookie("oauthRequest", UUID.randomUUID().toString()), "kakao", "test-state");
        MockHttpServletRequest missingState = callback(browser, "kakao", "test-state");
        missingState.removeParameter("state");
        assertThat(repository.loadAuthorizationRequest(unknownBrowser)).isNull();
        assertThat(repository.removeAuthorizationRequest(unknownBrowser, new MockHttpServletResponse())).isNull();
        assertThat(repository.loadAuthorizationRequest(missingState)).isNull();
        assertThat(repository.removeAuthorizationRequest(missingState, new MockHttpServletResponse())).isNull();
        assertThat(missingState.getAttribute(BrowserAuthorizationRequestRepository.VALIDATED)).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(browser, "kakao", "test-state"),
                new MockHttpServletResponse())).isSameAs(authorization);
    }

    @Test
    void acceptsCallbackImmediatelyBeforeExpiry() {
        when(clock.instant()).thenReturn(start.plusSeconds(599));
        assertThat(repository.removeAuthorizationRequest(callback(browser, "kakao", "test-state"),
                new MockHttpServletResponse())).isSameAs(authorization);
    }

    @Test
    void savingNullClearsBrowserRequestAndCookie() {
        MockHttpServletRequest request = callback(browser, "kakao", "test-state");
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(null, request, response);
        assertThat(repository.removeAuthorizationRequest(request, new MockHttpServletResponse())).isNull();
        assertThat(response.getCookie("oauthRequest").getMaxAge()).isZero();
        assertThat(response.getCookie("oauthRequest").isHttpOnly()).isTrue();
        assertThat(response.getCookie("oauthRequest").getSecure()).isTrue();
    }

    @Test
    void anotherBrowsersStateCannotConsumeEitherRequest() {
        OAuth2AuthorizationRequest otherAuthorization = OAuth2AuthorizationRequest.from(authorization).state("other-state").build();
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(otherAuthorization, new MockHttpServletRequest(), response);
        Cookie otherBrowser = response.getCookie("oauthRequest");
        assertThat(repository.removeAuthorizationRequest(callback(otherBrowser, "kakao", "test-state"),
                new MockHttpServletResponse())).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(browser, "kakao", "other-state"),
                new MockHttpServletResponse())).isNull();
        assertThat(repository.removeAuthorizationRequest(callback(browser, "kakao", "test-state"),
                new MockHttpServletResponse())).isSameAs(authorization);
        assertThat(repository.removeAuthorizationRequest(callback(otherBrowser, "kakao", "other-state"),
                new MockHttpServletResponse())).isSameAs(otherAuthorization);
    }

    @Test
    void concurrentCallbacksAuthenticateOnlyOnce() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<OAuth2AuthorizationRequest>> results = executor.invokeAll(List.of(
                    () -> repository.removeAuthorizationRequest(callback(browser, "kakao", "test-state"), new MockHttpServletResponse()),
                    () -> repository.removeAuthorizationRequest(callback(browser, "kakao", "test-state"), new MockHttpServletResponse())));
            int successfulCallbacks = (results.get(0).get() == null ? 0 : 1) + (results.get(1).get() == null ? 0 : 1);
            assertThat(successfulCallbacks).isEqualTo(1);
        }
    }

    private MockHttpServletRequest callback(Cookie cookie, String provider, String state) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/oauth/code/" + provider);
        if (cookie != null) {
            request.setCookies(cookie);
        }
        request.setParameter("state", state);
        return request;
    }
}
