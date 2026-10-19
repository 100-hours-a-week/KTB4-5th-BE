package com.dameokja.backend.auth.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

// iOS 홈 화면 앱은 카카오톡 간편 로그인 결과가 Safari로 돌아가 state 쿠키를 잃으므로, 카카오계정 로그인 화면을 요청한다.
public class StandaloneAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private static final String STANDALONE_PARAMETER = "standalone";
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public StandaloneAuthorizationRequestResolver(ClientRegistrationRepository registrations, String authorizationBaseUri) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, authorizationBaseUri);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withPromptLogin(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withPromptLogin(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest withPromptLogin(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null || !"1".equals(request.getParameter(STANDALONE_PARAMETER))) {
            return authorization;
        }
        Map<String, Object> parameters = new HashMap<>(authorization.getAdditionalParameters());
        parameters.put("prompt", "login");
        return OAuth2AuthorizationRequest.from(authorization).additionalParameters(parameters).build();
    }
}
