package com.dameokja.backend.user.application.registration;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.image.application.ImportedProfileImage;
import com.dameokja.backend.image.application.ProfileImageService;
import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OAuthSignupService {
    private final OAuthRegistrationStore registrations;
    private final UserRegistrationService userRegistrationService;
    private final AuthService authService;
    private final ProfileImageService profileImages;
    private final TransactionTemplate transaction;

    public OAuthSignupService(OAuthRegistrationStore registrations, UserRegistrationService userRegistrationService,
            AuthService authService, ProfileImageService profileImages,
            PlatformTransactionManager transactionManager) {
        this.registrations = registrations;
        this.userRegistrationService = userRegistrationService;
        this.authService = authService;
        this.profileImages = profileImages;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public SignupResult signup(String token, String nickname, boolean notificationSetting) {
        RegistrationClaim claim = registrations.claim(token);
        try (RegistrationClaimScope scope = new RegistrationClaimScope(registrations, claim)) {
            userRegistrationService.validateSocialRegistration(claim.identity(), nickname);
            try (ImportedProfileImage image = profileImages.importKakao(claim.identity().profileImageUrl())) {
                return transaction.execute(status -> {
                    scope.completeWithTransaction();
                    image.retainOnCommit();
                    RegistrationResult registration = userRegistrationService.registerSocial(
                            claim.identity(), nickname, notificationSetting, image.key());
                    TokenPair tokens = authService.loginSocial(registration.userId());
                    return new SignupResult(tokens, List.of(registration.refrigeratorId()));
                });
            }
        }
    }
}
