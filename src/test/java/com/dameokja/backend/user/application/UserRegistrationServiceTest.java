package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.RefrigeratorLifecycleService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
    @Mock
    private LoginIdPolicy loginIdPolicy;
    private UserRegistrationService userRegistrationService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        userRegistrationService = new UserRegistrationService(
                userRepository, refrigeratorLifecycleService,
                new UserRegistrationFactory(nicknamePolicy, loginIdPolicy, "default.png"), clock);
    }

    @Test
    void requestsUserAndPersonalRefrigeratorCreation() {
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserProfile registeredUserProfile = userRegistrationService.register(
                new RegisterUserCommand("User1", null, "login1", "hash"));

        ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUserCaptor.capture());
        User user = savedUserCaptor.getValue();
        assertThat(user.getNickname()).isEqualTo("User1");
        assertThat(user.getPasswordHash()).isEqualTo("hash");
        assertThat(user.getPasswordChangedAt()).isEqualTo(
                LocalDateTime.of(2026, 9, 17, 12, 0));
        verify(refrigeratorLifecycleService).createPersonal(user, "2026-09");
        assertThat(registeredUserProfile.profileImageKey()).isEqualTo("default.png");
        verify(nicknamePolicy).validate("User1");
        verify(userRepository).flush();
    }

    @Test
    void rejectsNicknamePolicyFailureBeforeUsingRepositories() {
        doThrow(new CustomException(UserExceptionCode.NICKNAME_PROHIBITED))
                .when(nicknamePolicy).validate("Bad1");
        assertUserError(UserExceptionCode.NICKNAME_PROHIBITED,
                () -> userRegistrationService.register(
                        new RegisterUserCommand("Bad1", null, null, null)));
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
                        new RegisterUserCommand("User1", null, "login1", "hash")));
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(refrigeratorLifecycleService);
    }

    private void assertUserError(UserExceptionCode expectedExceptionCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedExceptionCode));
    }
}
