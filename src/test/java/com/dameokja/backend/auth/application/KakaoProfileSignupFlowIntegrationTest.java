package com.dameokja.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.image.infrastructure.KakaoProfileImageDownloader;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.authentication.OAuthLoginService;
import com.dameokja.backend.user.application.registration.OAuthSignupService;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class KakaoProfileSignupFlowIntegrationTest extends ServiceIntegrationTest {
    private static final String SOURCE = "https://k.kakaocdn.net/profile.jpg";
    @Autowired OAuthSignupService signup;
    @Autowired OAuthRegistrationStore registrations;
    @Autowired OAuthLoginService login;
    @MockitoBean KakaoProfileImageDownloader downloader;
    @MockitoBean S3Client client;

    @Test
    void kakaoDefaultPhotoUsesOurDefaultAndLaterLoginKeepsIt() {
        OAuthIdentity identity = loadKakaoIdentity(true);
        Long userId = signup.signup(registrations.create(identity), "default1", false).tokenPair().userId();
        assertThat(savedKey(userId)).isEqualTo("profiles/default.png");
        verifyNoInteractions(downloader, client);
        assertLaterLoginKeepsKey(userId, identity, "profiles/default.png");
    }

    @Test
    void userPhotoIsCopiedAtSignupAndLaterLoginKeepsStoredS3Key() {
        OAuthIdentity identity = loadKakaoIdentity(false);
        when(downloader.download(SOURCE)).thenReturn(new KakaoProfileImageDownloader.DownloadedImage(new byte[]{1}, "image/png"));
        Long userId = signup.signup(registrations.create(identity), "photo1", false).tokenPair().userId();
        ArgumentCaptor<PutObjectRequest> uploaded = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(uploaded.capture(), any(RequestBody.class));
        String key = uploaded.getValue().key();
        assertThat(savedKey(userId)).isEqualTo(key).startsWith("test-images/profile/");
        clearInvocations(downloader, client);
        assertLaterLoginKeepsKey(userId, identity, key);
    }

    private void assertLaterLoginKeepsKey(Long userId, OAuthIdentity original, String key) {
        OAuthIdentity nextLogin = new OAuthIdentity(original.provider(), original.providerUserId(),
                original.email(), "https://k.kakaocdn.net/changed-after-signup.jpg");
        assertThat(login.login(nextLogin).tokens().userId()).isEqualTo(userId);
        assertThat(savedKey(userId)).isEqualTo(key);
        verifyNoInteractions(downloader, client);
    }

    private String savedKey(Long userId) {
        return userRepository.findById(userId).orElseThrow().getProfileImageKey();
    }

    private OAuthIdentity loadKakaoIdentity(boolean defaultImage) {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        delegate.setRestOperations(rest);
        String response = """
                {"id":123,"kakao_account":{"email":"member@example.com","is_email_valid":true,"is_email_verified":true,
                "profile":{"is_default_image":%s,"profile_image_url":"%s"}}}
                """.formatted(defaultImage, SOURCE);
        server.expect(requestTo("https://provider.example.com/userinfo")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        OAuthIdentity identity = new SocialOAuth2UserService(delegate).loadUser(userRequest()).identity();
        server.verify();
        return identity;
    }

    private OAuth2UserRequest userRequest() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("kakao")
                .clientId("test-client").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://service.example.com/callback").authorizationUri("https://provider.example.com/authorize")
                .tokenUri("https://provider.example.com/token").userInfoUri("https://provider.example.com/userinfo")
                .userNameAttributeName("id").build();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-provider-token", Instant.EPOCH, Instant.EPOCH.plusSeconds(600));
        return new OAuth2UserRequest(registration, access);
    }
}
