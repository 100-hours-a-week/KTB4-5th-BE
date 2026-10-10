package com.dameokja.backend.global.security;

import com.dameokja.backend.auth.application.SocialOAuth2UserService;
import com.dameokja.backend.auth.infrastructure.BrowserAuthorizationRequestRepository;
import com.dameokja.backend.auth.infrastructure.DiscardingAuthorizedClientRepository;
import com.dameokja.backend.auth.presentation.OAuthCallbackHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OAuthSecurityConfig {
    private final BrowserAuthorizationRequestRepository requests;
    private final DiscardingAuthorizedClientRepository clients;
    private final SocialOAuth2UserService users;
    private final OAuthCallbackHandler callback;

    public void configure(HttpSecurity http) {
        http.oauth2Login(oauth -> oauth.authorizedClientRepository(clients)
                .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/oauth").authorizationRequestRepository(requests))
                .redirectionEndpoint(endpoint -> endpoint.baseUri("/api/v1/auth/oauth/code/*"))
                .userInfoEndpoint(endpoint -> endpoint.userService(users))
                .successHandler(callback).failureHandler(callback));
    }
}
