package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.domain.UserCredentials;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UserRegistrationFactory {
    private final NicknamePolicy nicknamePolicy;
    private final LoginIdPolicy loginIdPolicy;
    private final String defaultProfileImageKey;

    public UserRegistrationFactory(NicknamePolicy nicknamePolicy, LoginIdPolicy loginIdPolicy,
            @Value("${app.user.default-profile-image-key:profiles/default.png}")
            String defaultProfileImageKey) {
        this.nicknamePolicy = nicknamePolicy;
        this.loginIdPolicy = loginIdPolicy;
        this.defaultProfileImageKey = defaultProfileImageKey;
    }

    public User create(RegisterUserCommand registerUserCommand, LocalDateTime registeredAt) {
        nicknamePolicy.validate(registerUserCommand.nickname());
        loginIdPolicy.validate(registerUserCommand.loginId());
        validatePasswordHash(registerUserCommand.passwordHash());
        String profileImageKey = registerUserCommand.profileImageKey() == null
                ? defaultProfileImageKey : registerUserCommand.profileImageKey();
        UserCredentials userCredentials = new UserCredentials(registerUserCommand.loginId(),
                registerUserCommand.passwordHash(), registeredAt);
        return new User(registerUserCommand.nickname(), profileImageKey, userCredentials);
    }

    private void validatePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new CustomException(UserExceptionCode.PASSWORD_REQUIRED);
        }
    }
}
