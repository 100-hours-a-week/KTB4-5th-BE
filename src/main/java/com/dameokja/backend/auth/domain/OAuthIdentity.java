package com.dameokja.backend.auth.domain;

public record OAuthIdentity(OAuthProvider provider, String providerUserId, String email, String profileImageUrl) {
    public OAuthIdentity(OAuthProvider provider, String providerUserId, String email) {
        this(provider, providerUserId, email, null);
    }

    @Override
    public String toString() { return "OAuthIdentity[redacted]"; }
}
