package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserRegistrationFactoryTest {
    private final NicknamePolicy nicknamePolicy = mock(NicknamePolicy.class);
    private final LoginIdPolicy loginIdPolicy = new LoginIdPolicy(
            new NicknameAndLoginIdValidator(mock(ProhibitedWordChecker.class)));
    private final UserRegistrationFactory userRegistrationFactory =
            new UserRegistrationFactory(nicknamePolicy, loginIdPolicy, "default.png");
    private final LocalDateTime registeredAt = LocalDateTime.of(2026, 9, 30, 23, 59, 59);

    @Test
    void appliesDefaultImageAndProvidedRegistrationTime() {
        User user = userRegistrationFactory.create(
                new RegisterUserCommand("회원이름", null, "login1", "hash"), registeredAt);
        assertThat(user.getProfileImageKey()).isEqualTo("default.png");
        assertThat(user.getLoginId()).isEqualTo("login1");
        assertThat(user.getPasswordHash()).isEqualTo("hash");
        assertThat(user.getPasswordChangedAt()).isEqualTo(registeredAt);
        verify(nicknamePolicy).validate("회원이름");
    }

    @Test
    void preservesProvidedInitialImage() {
        User user = userRegistrationFactory.create(
                new RegisterUserCommand("회원이름", "custom.png", "login1", "hash"), registeredAt);
        assertThat(user.getProfileImageKey()).isEqualTo("custom.png");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingLoginId(String loginId) {
        assertRejected(new RegisterUserCommand("회원이름", null, loginId, "hash"),
                UserExceptionCode.LOGIN_ID_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a_b", "한 글", "a@b"})
    void rejectsInvalidLoginId(String loginId) {
        assertRejected(new RegisterUserCommand("회원이름", null, loginId, "hash"),
                UserExceptionCode.LOGIN_ID_FORMAT_INVALID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingPasswordHash(String passwordHash) {
        assertRejected(new RegisterUserCommand("회원이름", null, "login1", passwordHash),
                UserExceptionCode.PASSWORD_REQUIRED);
    }

    private void assertRejected(RegisterUserCommand command, UserExceptionCode expectedCode) {
        assertThatThrownBy(() -> userRegistrationFactory.create(command, registeredAt))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedCode));
    }
}
