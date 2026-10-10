package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.refrigerator.application.RefrigeratorLifecycleService;
import com.dameokja.backend.user.domain.SocialAccount;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.SocialAccountRepository;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserRegistrationService {
    private final UserRepository userRepository;
    private final RefrigeratorLifecycleService refrigeratorLifecycleService;
    private final NotificationPreferenceService notificationPreferenceService;
    private final UserRegistrationFactory userRegistrationFactory;
    private final Clock clock;

    private final NicknamePolicy nicknamePolicy;
    private final LoginIdPolicy loginIdPolicy;
    private final PasswordEncoder passwordEncoder;
    private final SocialAccountRepository socialAccountRepository;

    public RegistrationResult register(RegisterUserCommand registerUserCommand) {
        LocalDateTime registeredAt = now();
        String nickname = validateInput(registerUserCommand);
        try {
            validateUniqueAccount(nickname, registerUserCommand.loginId());
            String passwordHash = passwordEncoder.encode(registerUserCommand.password());
            User newUser = userRegistrationFactory.create(registerUserCommand, nickname, passwordHash, registeredAt);
            User user = userRepository.save(newUser);
            Long refrigeratorId = refrigeratorLifecycleService.createPersonal(
                    user, YearMonth.from(registeredAt).toString());
            notificationPreferenceService.createDefaults(user);
            userRepository.flush();
            return new RegistrationResult(user.getId(), refrigeratorId);
        } catch (DataIntegrityViolationException dataIntegrityViolationException) {
            throw UserConstraintExceptionTranslator.translate(dataIntegrityViolationException);
        }
    }

    public RegistrationResult registerSocial(OAuthIdentity identity, String nickname, boolean notificationSetting) {
        nicknamePolicy.validate(nickname);
        if (userRepository.existsByNickname(nickname)) {
            throw new CustomException(UserExceptionCode.NICKNAME_DUPLICATE);
        }
        if (socialAccountRepository.findByProviderAndProviderUserId(identity.provider(), identity.providerUserId()).isPresent()) {
            throw new CustomException(UserExceptionCode.SOCIAL_ACCOUNT_DUPLICATE);
        }
        try {
            User user = userRepository.save(userRegistrationFactory.createSocial(nickname));
            socialAccountRepository.save(new SocialAccount(
                    user, identity.provider(), identity.providerUserId(), identity.email()));
            Long refrigeratorId = refrigeratorLifecycleService.createPersonal(user, YearMonth.from(now()).toString());
            notificationPreferenceService.create(user, notificationSetting);
            userRepository.flush();
            return new RegistrationResult(user.getId(), refrigeratorId);
        } catch (DataIntegrityViolationException exception) {
            throw UserConstraintExceptionTranslator.translate(exception);
        }
    }

    private void validateUniqueAccount(String nickname, String loginId) {
        if (userRepository.existsByNickname(nickname)) {
            throw new CustomException(UserExceptionCode.NICKNAME_DUPLICATE);
        }
        if (userRepository.existsByLoginId(loginId)) {
            throw new CustomException(UserExceptionCode.LOGIN_ID_DUPLICATE);
        }
    }

    private LocalDateTime now() {
        return BusinessTime.now(clock);
    }

    private String validateInput(RegisterUserCommand command) {
        loginIdPolicy.validate(command.loginId());
        String nickname = command.nickname();
        if (nickname == null || nickname.codePoints().allMatch(
                codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) {
            nickname = command.loginId();
        }
        nicknamePolicy.validate(nickname);
        return nickname;
    }
}
