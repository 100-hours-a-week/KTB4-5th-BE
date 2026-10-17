package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.image.application.ImportedProfileImage;
import com.dameokja.backend.image.application.ProfileImageService;
import com.dameokja.backend.user.domain.RegistrationClaim;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OAuthSignupService {
    private final OAuthRegistrationStore registrations;
    private final UserRegistrationService userRegistrationService;
    private final AuthService authService;
    private final ApplicationEventPublisher events;
    private final ProfileImageService profileImages;
    private final TransactionTemplate transaction;

    public OAuthSignupService(OAuthRegistrationStore registrations, UserRegistrationService userRegistrationService,
            AuthService authService, ApplicationEventPublisher events, ProfileImageService profileImages,
            PlatformTransactionManager transactionManager) {
        this.registrations = registrations;
        this.userRegistrationService = userRegistrationService;
        this.authService = authService;
        this.events = events;
        this.profileImages = profileImages;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public SignupResult signup(String token, String nickname, boolean notificationSetting) {
        RegistrationClaim claim = registrations.claim(token);
        try (ImportedProfileImage image = profileImages.importKakao(claim.identity().profileImageUrl())) {
            return transaction.execute(status -> {
                image.retainOnCommit();
                events.publishEvent(new RegistrationClaimed(claim));
                RegistrationResult registration = userRegistrationService.registerSocial(
                        claim.identity(), nickname, notificationSetting, image.key());
                TokenPair tokens = authService.loginSocial(registration.userId());
                return new SignupResult(tokens, List.of(registration.refrigeratorId()));
            });
        } catch (RuntimeException exception) {
            // 트랜잭션을 시작하지 못했을 때도 같은 가입 토큰으로 다시 시도할 수 있어야 한다.
            releaseAfterFailure(claim, exception);
            throw exception;
        }
    }

    private void releaseAfterFailure(RegistrationClaim claim, RuntimeException original) {
        try {
            registrations.release(claim);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    public record RegistrationClaimed(RegistrationClaim claim) {}
}
