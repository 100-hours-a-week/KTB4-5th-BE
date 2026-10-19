package com.dameokja.backend.auth.application;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

public record OAuthPrincipal(OAuthIdentity identity) implements OAuth2User {
    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("provider", identity.provider().name(),
                "providerUserId", identity.providerUserId(), "email", identity.email());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("OAUTH2_USER"));
    }

    @Override
    public String getName() { return identity.provider().name() + ":" + identity.providerUserId(); }

    @Override
    public String toString() { return "OAuthPrincipal[redacted]"; }
}
