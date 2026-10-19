package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {
    private final SocialAccountRepository accounts;
    private final OAuthRegistrationStore registrations;
    private final AuthService auth;

    @Transactional
    public LoginResult login(OAuthIdentity identity) {
        return accounts.findByProviderAndProviderUserId(identity.provider(), identity.providerUserId())
                .map(account -> loginExisting(account, identity.email()))
                .orElseGet(() -> new LoginResult(null, registrations.create(identity)));
    }

    private LoginResult loginExisting(SocialAccount account, String email) {
        TokenPair tokens = auth.loginSocial(account.getUser().getId());
        account.updateEmail(email);
        return new LoginResult(tokens, null);
    }

    public record LoginResult(TokenPair tokens, String registrationToken) {}
}
