package com.dameokja.backend.auth.application;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class SocialOAuth2UserServiceTest {
    private MockRestServiceServer server;
    private SocialOAuth2UserService service;

    @BeforeEach
    void setup() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        server = MockRestServiceServer.bindTo(restTemplate).build();
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        delegate.setRestOperations(restTemplate);
        service = new SocialOAuth2UserService(delegate);
    }

    @ParameterizedTest
    @ValueSource(longs = {123L, 9007199254740993L})
    void loadsVerifiedKakaoIdentityThroughStandardUserService(long id) {
        respond(profile(Long.toString(id), "member@example.com", "true", "true"));
        OAuthPrincipal principal = service.loadUser(request("kakao"));
        assertThat(principal.identity())
                .isEqualTo(new OAuthIdentity(OAuthProvider.KAKAO, Long.toString(id), "member@example.com"));
        assertThat(principal.getName()).isEqualTo("KAKAO:" + id);
        assertThat(principal.getAttributes()).containsOnlyKeys("provider", "providerUserId", "email");
        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("OAUTH2_USER");
        assertThat(principal.toString()).doesNotContain("member@example.com", Long.toString(id));
        assertThat(principal.identity().toString()).doesNotContain("member@example.com", Long.toString(id));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "\"123\"", "null", "9223372036854775808"})
    void rejectsInvalidProviderIdentifier(String id) {
        respond(profile(id, "member@example.com", "true", "true"));
        assertInvalidProfile();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "member@example.com ", "member@example.com\n"})
    void rejectsMissingOrMalformedEmail(String email) {
        respond(profile("123", email, "true", "true"));
        assertInvalidProfile();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "null", "\"true\""})
    void rejectsInvalidOrUnverifiedEmail(String flag) {
        respond(profile("123", "member@example.com", flag, "true"));
        assertInvalidProfile();
        respond(profile("123", "member@example.com", "true", flag));
        assertInvalidProfile();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":123}", "{\"id\":123,\"kakao_account\":null}",
            "{\"id\":123,\"kakao_account\":[]}",
            "{\"id\":123,\"kakao_account\":{\"is_email_valid\":true,\"is_email_verified\":true}}"})
    void rejectsIncompleteAccount(String response) {
        respond(response);
        assertInvalidProfile();
    }

    @Test
    void rejectsUnsupportedProviderWithoutSendingUserInfoRequest() {
        assertThatThrownBy(() -> service.loadUser(request("google")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode()).isEqualTo("unsupported_provider"));
        server.verify();
    }

    @Test
    void preservesStandardUserInfoAuthenticationFailure() {
        server.expect(requestTo("https://provider.example.com/userinfo")).andRespond(withUnauthorizedRequest());
        assertThatThrownBy(() -> service.loadUser(request("kakao")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
    }

    private void assertInvalidProfile() {
        assertThatThrownBy(() -> service.loadUser(request("kakao")))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_user_info_response"));
        server.verify();
        server.reset();
    }

    private void respond(String response) {
        server.expect(requestTo("https://provider.example.com/userinfo"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-provider-token"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private String profile(String id, String email, String valid, String verified) {
        String escapedEmail = email.replace("\n", "\\n");
        return "{\"id\":" + id + ",\"kakao_account\":{\"email\":\"" + escapedEmail
                + "\",\"is_email_valid\":" + valid + ",\"is_email_verified\":" + verified + "}}";
    }

    private OAuth2UserRequest request(String provider) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(provider)
                .clientId("test-client").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://service.example.com/callback").authorizationUri("https://provider.example.com/authorize")
                .tokenUri("https://provider.example.com/token").userInfoUri("https://provider.example.com/userinfo")
                .userNameAttributeName("id").build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-provider-token", Instant.EPOCH, Instant.EPOCH.plusSeconds(600));
        return new OAuth2UserRequest(registration, token);
    }
}
