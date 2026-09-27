package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserCredentials;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UserRegistrationFactory {
    private final String defaultProfileImageKey;

    public UserRegistrationFactory(
            @Value("${app.user.default-profile-image-key:profiles/default.png}") String defaultProfileImageKey) {
        this.defaultProfileImageKey = defaultProfileImageKey;
    }

    public User create(RegisterUserCommand command, String nickname, String passwordHash, LocalDateTime registeredAt) {
        String profileImageKey = command.profileImageKey() == null
                ? defaultProfileImageKey : command.profileImageKey();
        UserCredentials credentials = new UserCredentials(command.loginId(), passwordHash, registeredAt);
        return new User(nickname, profileImageKey, credentials);
    }
}
