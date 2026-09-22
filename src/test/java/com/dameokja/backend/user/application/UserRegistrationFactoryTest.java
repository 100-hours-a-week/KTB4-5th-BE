package com.dameokja.backend.user.application;

import com.dameokja.backend.user.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserRegistrationFactoryTest {
    private final UserRegistrationFactory userRegistrationFactory =
            new UserRegistrationFactory("default.png");
    private final LocalDateTime registeredAt = LocalDateTime.of(2026, 9, 30, 23, 59, 59);

    @Test
    void appliesDefaultImageAndProvidedRegistrationTime() {
        User user = userRegistrationFactory.create(
                new RegisterUserCommand("회원이름", null, "login1", "pass1234"), "회원이름", "hash", registeredAt);
        assertThat(user.getProfileImageKey()).isEqualTo("default.png");
        assertThat(user.getLoginId()).isEqualTo("login1");
        assertThat(user.getPasswordHash()).isEqualTo("hash");
        assertThat(user.getPasswordChangedAt()).isEqualTo(registeredAt);
    }

    @Test
    void preservesProvidedInitialImage() {
        User user = userRegistrationFactory.create(
                new RegisterUserCommand("회원이름", "custom.png", "login1", "pass1234"), "회원이름", "hash", registeredAt);
        assertThat(user.getProfileImageKey()).isEqualTo("custom.png");
    }

}
