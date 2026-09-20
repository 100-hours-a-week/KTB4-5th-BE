package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.refrigerator.application.RefrigeratorLifecycleService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRegistrationFlushTest {
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

    @ParameterizedTest
    @CsvSource({
            "save, uk_users_nickname, NICKNAME_DUPLICATE",
            "save, uk_users_login_id, LOGIN_ID_DUPLICATE",
            "flush, uk_users_nickname, NICKNAME_DUPLICATE",
            "flush, uk_users_login_id, LOGIN_ID_DUPLICATE"
    })
    void translatesRegistrationConstraintFailure(
            String failureStage, String constraintName, UserExceptionCode expectedCode) {
        DataIntegrityViolationException databaseException = constraintFailure(constraintName);
        if (failureStage.equals("save")) {
            when(userRepository.save(any(User.class))).thenThrow(databaseException);
        } else {
            stubRegistration();
            doThrow(databaseException).when(userRepository).flush();
        }

        assertThatThrownBy(this::register)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedCode));
    }

    private void stubRegistration() {
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void register() {
        userRegistrationService.register(new RegisterUserCommand("User2", null, "login1", "hash"));
    }

    @Test
    void preservesUnknownFlushFailure() {
        stubRegistration();
        DataIntegrityViolationException databaseException = constraintFailure("other_constraint");
        doThrow(databaseException).when(userRepository).flush();
        assertThatThrownBy(this::register).isSameAs(databaseException);
    }

    private DataIntegrityViolationException constraintFailure(String constraintName) {
        SQLException sqlException = new SQLException("Duplicate entry", "23000", 1062);
        return new DataIntegrityViolationException("Constraint violation",
                new ConstraintViolationException(
                        "Constraint violation", sqlException, constraintName));
    }
}
