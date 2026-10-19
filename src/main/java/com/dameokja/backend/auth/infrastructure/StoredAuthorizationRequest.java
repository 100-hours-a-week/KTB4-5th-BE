package com.dameokja.backend.auth.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

// Security 객체의 구현 타입에 의존하지 않고 인증 코드 교환에 필요한 필드만 보관한다.
record StoredAuthorizationRequest(String authorizationUri, String clientId, String redirectUri,
        Set<String> scopes, String state, Map<String, Object> additionalParameters,
        Map<String, Object> attributes, String authorizationRequestUri, Instant expiresAt) {
    static StoredAuthorizationRequest from(OAuth2AuthorizationRequest request, Instant expiresAt) {
        return new StoredAuthorizationRequest(request.getAuthorizationUri(), request.getClientId(),
                request.getRedirectUri(), request.getScopes(), request.getState(),
                request.getAdditionalParameters(), request.getAttributes(), request.getAuthorizationRequestUri(), expiresAt);
    }

    OAuth2AuthorizationRequest authorization() {
        return OAuth2AuthorizationRequest.authorizationCode().authorizationUri(authorizationUri)
                .clientId(clientId).redirectUri(redirectUri).scopes(scopes).state(state)
                .additionalParameters(additionalParameters).attributes(attributes)
                .authorizationRequestUri(authorizationRequestUri).build();
    }
}
