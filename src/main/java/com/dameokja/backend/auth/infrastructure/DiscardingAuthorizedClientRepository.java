package com.dameokja.backend.auth.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Repository;

@Repository
public class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
            Authentication principal, HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {
        // 제공자 토큰은 사용자 정보 조회에만 쓰고 서버·세션에 보관하지 않는다.
    }

    @Override
    public void removeAuthorizedClient(String registrationId, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {}
}
