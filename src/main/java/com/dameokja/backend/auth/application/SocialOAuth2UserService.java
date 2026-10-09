package com.dameokja.backend.auth.application;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class SocialOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s@]+@[^\\s@]+");
    private final DefaultOAuth2UserService delegate;

    public SocialOAuth2UserService() { this(new DefaultOAuth2UserService()); }

    SocialOAuth2UserService(DefaultOAuth2UserService delegate) {
        this.delegate = delegate;
        delegate.setAttributesConverter(request -> this::kakaoAttributes);
    }

    @Override
    public OAuthPrincipal loadUser(OAuth2UserRequest request) {
        if (!"kakao".equals(request.getClientRegistration().getRegistrationId())) {
            throw failure("unsupported_provider");
        }
        OAuth2User user = delegate.loadUser(request);
        return new OAuthPrincipal(new OAuthIdentity(OAuthProvider.KAKAO,
                user.getName(), user.getAttribute("email")));
    }

    private Map<String, Object> kakaoAttributes(Map<String, Object> attributes) {
        // Spring이 principal을 만들기 전에 필수 식별자와 이메일을 검증한다.
        return Map.of("id", kakaoUserId(attributes.get("id")),
                "email", kakaoEmail(attributes.get("kakao_account")));
    }

    private String kakaoUserId(Object id) {
        // JSON 정수의 원래 정밀도를 유지하고 소수·문자열·BIGINT 범위 초과를 거절한다.
        if (!(id instanceof Long || id instanceof Integer)) {
            throw failure("invalid_user_info_response");
        }
        long numericId = ((Number) id).longValue();
        if (numericId <= 0) {
            throw failure("invalid_user_info_response");
        }
        return Long.toString(numericId);
    }

    private String kakaoEmail(Object account) {
        if (!(account instanceof Map<?, ?> attributes)
                || !Boolean.TRUE.equals(attributes.get("is_email_valid"))
                || !Boolean.TRUE.equals(attributes.get("is_email_verified"))
                || !(attributes.get("email") instanceof String email)
                || email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw failure("invalid_user_info_response");
        }
        return email;
    }

    private OAuth2AuthenticationException failure(String code) {
        // 제공자 응답과 개인정보를 인증 예외 메시지에 포함하지 않는다.
        return new OAuth2AuthenticationException(new OAuth2Error(code));
    }
}
