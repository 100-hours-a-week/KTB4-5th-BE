package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.refrigerator.application.RefrigeratorLifecycleService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private RefrigeratorLifecycleService refrigeratorLifecycleService;
    @Mock
    private NicknamePolicy nicknamePolicy;
    private final LoginIdPolicy loginIdPolicy = new LoginIdPolicy(mock(ProhibitedWordChecker.class));
    private UserRegistrationService userRegistrationService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        userRegistrationService = new UserRegistrationService(
                userRepository, refrigeratorLifecycleService,
                new UserRegistrationFactory("default.png"), clock,
                nicknamePolicy, loginIdPolicy, new PasswordPolicy(), new BCryptPasswordEncoder());
    }

    @Test
    void requestsUserAndPersonalRefrigeratorCreation() {
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(refrigeratorLifecycleService.createPersonal(any(), any())).thenReturn(9L);
        RegistrationResult result = userRegistrationService.register(
                new RegisterUserCommand("User1", null, "login1", "pass1234"));

        ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUserCaptor.capture());
        User user = savedUserCaptor.getValue();
        assertThat(user.getNickname()).isEqualTo("User1");
        assertThat(new BCryptPasswordEncoder().matches("pass1234", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordChangedAt()).isEqualTo(
                LocalDateTime.of(2026, 9, 17, 12, 0));
        verify(refrigeratorLifecycleService).createPersonal(user, "2026-09");
        assertThat(user.getProfileImageKey()).isEqualTo("default.png");
        assertThat(result.refrigeratorId()).isEqualTo(9L);
        verify(nicknamePolicy).validate("User1");
        verify(userRepository).flush();
    }

    @Test
    void rejectsNicknamePolicyFailureBeforeUsingRepositories() {
        doThrow(new CustomException(UserExceptionCode.NICKNAME_PROHIBITED))
                .when(nicknamePolicy).validate("Bad1");
        assertUserError(UserExceptionCode.NICKNAME_PROHIBITED,
                () -> userRegistrationService.register(
                        new RegisterUserCommand("Bad1", null, "login1", "pass1234")));
        verifyNoInteractions(userRepository, refrigeratorLifecycleService);
    }

    @ParameterizedTest
    @CsvSource({"nickname,NICKNAME_DUPLICATE", "login,LOGIN_ID_DUPLICATE"})
    void rejectsExistingNicknameOrLogin(
            String duplicateField, UserExceptionCode expectedExceptionCode) {
        if (duplicateField.equals("nickname")) {
            when(userRepository.existsByNickname("User1")).thenReturn(true);
        } else {
            when(userRepository.existsByLoginId("login1")).thenReturn(true);
        }
        assertUserError(expectedExceptionCode,
                () -> userRegistrationService.register(
                        new RegisterUserCommand("User1", null, "login1", "pass1234")));
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(refrigeratorLifecycleService);
    }

    @ParameterizedTest
    @CsvSource({"login1,,PASSWORD_REQUIRED", "login1,' ',PASSWORD_REQUIRED",
            "login1,pass123,PASSWORD_FORMAT_INVALID", "login1,password,PASSWORD_FORMAT_INVALID",
            "login1,12345678,PASSWORD_FORMAT_INVALID", "login1,한글비밀번호12,PASSWORD_FORMAT_INVALID"})
    void validatesRawInputWithoutSignup(String loginId, String password, UserExceptionCode expected) {
        assertUserError(expected, () -> userRegistrationService.register(
                new RegisterUserCommand(null, null, loginId, password)));
        verifyNoInteractions(userRepository, refrigeratorLifecycleService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "\u3000", "\u00a0"})
    void defaultsNicknameBeforeValidationAndDuplicateCheck(String nickname) {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        userRegistrationService.register(new RegisterUserCommand(nickname, null, "login1", "pass1234"));
        verify(nicknamePolicy).validate("login1");
        verify(userRepository).existsByNickname("login1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "한"})
    void checksBcryptByteBoundaryWithoutChangingPassword(String character) {
        String password = "A1" + (character.equals("a") ? "a".repeat(70) : "한".repeat(23) + "a");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        userRegistrationService.register(new RegisterUserCommand(null, null, "login1", password));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(new BCryptPasswordEncoder().matches(password, saved.getValue().getPasswordHash())).isTrue();
        assertUserError(UserExceptionCode.PASSWORD_FORMAT_INVALID, () -> userRegistrationService.register(
                new RegisterUserCommand(null, null, "login2", password + "a")));
    }

    private void assertUserError(UserExceptionCode expectedExceptionCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedExceptionCode));
    }
}
