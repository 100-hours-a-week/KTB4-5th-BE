package com.dameokja.backend.auth.integration;

import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.support.ServiceIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class KakaoOAuthFlowTest extends ServiceIntegrationTest {
    private static final MockKakaoServer KAKAO = new MockKakaoServer();
    private static final String CALLBACK = "/api/v1/auth/oauth/code/kakao";
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired OAuth2AuthorizedClientRepository clients;
    private MockMvc mvc;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        String prefix = "spring.security.oauth2.client.provider.kakao.";
        registry.add(prefix + "authorization-uri", () -> KAKAO.url("/authorize"));
        registry.add(prefix + "token-uri", () -> KAKAO.url("/token"));
        registry.add(prefix + "user-info-uri", () -> KAKAO.url("/userinfo"));
    }

    @AfterAll
    static void stopProvider() { KAKAO.close(); }

    @BeforeEach
    void web() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        KAKAO.reset();
        assertThat(context.getBean("springSecurityFilterChain", FilterChainProxy.class).getFilterChains()).hasSize(1);
    }

    @Test
    void signupAndOAuthReloginConnectProviderIdentityToServiceCookies() throws Exception {
        MvcResult pending = callback(start(), "code", "new-member");
        assertThat(pending.getResponse().getRedirectedUrl()).isEqualTo("http://localhost:3000/signup");
        assertThat(pending.getResponse().getCookie("accessToken")).isNull();
        MvcResult joined = signup(pending.getResponse().getCookie("registrationToken"));
        long userId = jwt.parseAccessTokenPayload(joined.getResponse().getCookie("accessToken").getValue()).userId();
        assertThat(joined.getResponse().getCookie("refreshToken")).isNotNull();
        MvcResult loggedIn = callback(start(), "code", "existing-member");
        assertThat(loggedIn.getResponse().getRedirectedUrl()).isEqualTo("http://localhost:3000/");
        assertThat(jwt.parseAccessTokenPayload(loggedIn.getResponse().getCookie("accessToken").getValue()).userId()).isEqualTo(userId);
        assertThat(loggedIn.getResponse().getCookie("refreshToken")).isNotNull();
        assertProviderRequests();
    }

    private void assertProviderRequests() {
        assertThat(KAKAO.tokenRequests).isEqualTo(2);
        assertThat(KAKAO.userRequests).isEqualTo(2);
        assertThat(KAKAO.tokenBody).contains("grant_type=authorization_code", "client_id=test-kakao-client", "client_secret=test-kakao-secret",
                "code=existing-member", "redirect_uri=http%3A%2F%2Flocalhost%2Fapi%2Fv1%2Fauth%2Foauth%2Fcode%2Fkakao");
        assertThat(KAKAO.authorization).isEqualTo("Bearer provider-access");
        assertThat(KAKAO.tokenMethod).isEqualTo("POST");
        assertThat(KAKAO.tokenContentType).startsWith("application/x-www-form-urlencoded");
    }

    @Test
    void stateIsBrowserBoundSingleUseAndCancellationRequiresValidatedState() throws Exception {
        MvcResult started = start();
        String state = state(started);
        MvcResult wrong = request(get(CALLBACK).param("code", "valid").param("state", "wrong")
                .cookie(started.getResponse().getCookie("oauthRequest")));
        assertFailure(wrong, "OAUTH_LOGIN_FAILED");
        assertFailure(request(get(CALLBACK).param("code", "valid").param("state", state)), "OAUTH_LOGIN_FAILED");
        assertFailure(request(get(CALLBACK).param("code", "valid").cookie(started.getResponse().getCookie("oauthRequest"))), "OAUTH_LOGIN_FAILED");
        assertThat(KAKAO.tokenRequests).isZero();
        assertFailure(callback(started, "error", "access_denied"), "OAUTH_CANCELLED");
        assertFailure(callback(started, "error", "access_denied"), "OAUTH_LOGIN_FAILED");
        assertThat(KAKAO.tokenRequests).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-code", "unverified-email"})
    void providerFailuresDoNotCreateAccountsOrServiceTokens(String failure) throws Exception {
        KAKAO.verified = !failure.equals("unverified-email");
        MvcResult failed = callback(start(), "code", failure);
        assertFailure(failed, "OAUTH_LOGIN_FAILED");
        assertThat(failed.getResponse().getCookie("accessToken")).isNull();
        assertThat(failed.getResponse().getCookie("registrationToken")).isNull();
        assertEmptyDatabase();
    }

    private MvcResult start() throws Exception {
        MvcResult started = request(get("/api/v1/auth/oauth/kakao").cookie(new Cookie("accessToken", "stale")));
        assertThat(started.getResponse().getStatus()).isEqualTo(302);
        assertThat(started.getResponse().getRedirectedUrl()).startsWith(KAKAO.url("/authorize"))
                .contains("response_type=code", "scope=account_email", "client_id=test-kakao-client");
        assertThat(started.getResponse().getCookie("oauthRequest").getPath()).isEqualTo("/api/v1/auth/oauth");
        return started;
    }

    private MvcResult callback(MvcResult started, String parameter, String value) throws Exception {
        return request(get(CALLBACK).param(parameter, value).param("state", state(started))
                .cookie(started.getResponse().getCookie("oauthRequest")));
    }

    private String state(MvcResult started) {
        return URLDecoder.decode(UriComponentsBuilder.fromUriString(started.getResponse().getRedirectedUrl())
                .build().getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
    }

    private MvcResult signup(Cookie registration) throws Exception {
        Cookie csrf = request(get("/api/v1/auth/csrf")).getResponse().getCookie("XSRF-TOKEN");
        MvcResult result = request(post("/api/v1/users/oauth").cookie(registration, csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(signupBody()));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return result;
    }

    private String signupBody() { return "{\"nickname\":\"dave\",\"notificationSetting\":false}"; }

    private MvcResult request(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mvc.perform(builder).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("kakao", null, result.getRequest())).isNull();
        return result;
    }

    private void assertFailure(MvcResult result, String error) {
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=" + error);
    }
}
