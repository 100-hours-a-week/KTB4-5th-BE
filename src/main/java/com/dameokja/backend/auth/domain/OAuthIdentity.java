package com.dameokja.backend.auth.domain;

public record OAuthIdentity(OAuthProvider provider, String providerUserId, String email) {
    @Override
    public String toString() { return "OAuthIdentity[redacted]"; }
}
