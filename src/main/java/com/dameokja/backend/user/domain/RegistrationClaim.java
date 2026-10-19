package com.dameokja.backend.user.domain;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import java.util.UUID;

public record RegistrationClaim(UUID token, UUID claimId, OAuthIdentity identity) {
    @Override
    public String toString() { return "RegistrationClaim[redacted]"; }
}
