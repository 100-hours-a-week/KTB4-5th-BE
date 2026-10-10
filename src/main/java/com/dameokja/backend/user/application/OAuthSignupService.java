package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OAuthSignupService {
    private final OAuthRegistrationStore registrations;
    private final UserRegistrationService userRegistrationService;
    private final AuthService authService;
    private final ApplicationEventPublisher events;

    @Transactional
    public SignupResult signup(String token, String nickname, boolean notificationSetting) {
        RegistrationClaim claim = registrations.claim(token);
        events.publishEvent(new RegistrationClaimed(claim));
        RegistrationResult registration = userRegistrationService.registerSocial(claim.identity(), nickname, notificationSetting);
        TokenPair tokens = authService.loginSocial(registration.userId());
        return new SignupResult(tokens, List.of(registration.refrigeratorId()));
    }

    public record RegistrationClaimed(RegistrationClaim claim) {}
}
