package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserCredentials;
import com.dameokja.backend.user.infrastructure.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserAuthenticationServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserAuthenticationService service =
            new UserAuthenticationService(repository, encoder);

    private User user() {
        User user = new User("User", "profile.png",
                new UserCredentials("user", encoder.encode("password"), LocalDateTime.now()));
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    @Test
    void verifiesPasswordAndReturnsOnlyIdAndRole() {
        User user = user();
        when(repository.findIdByLoginId("user")).thenReturn(Optional.of(1L));
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        assertThat(service.authenticate("user", "password"))
                .isEqualTo(new AuthenticatedUser(1L, user.getRole()));
        assertCode(() -> service.authenticate("user", "wrong"),
                AuthExceptionCode.INVALID_CREDENTIALS);
    }

    @Test
    void unknownUserRequiresAuthentication() {
        when(repository.findIdByLoginId("unknown")).thenReturn(Optional.empty());
        assertCode(() -> service.authenticate("unknown", "password"),
                AuthExceptionCode.LOGIN_ID_NOT_FOUND);
        assertCode(() -> service.authenticate("unknown", "한".repeat(30)),
                AuthExceptionCode.LOGIN_ID_NOT_FOUND);
    }

    @Test
    void missingAndWithdrawnUsersCannotRenewAuthentication() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertCode(() -> service.findActive(1L), SecurityExceptionCode.USER_NOT_ACTIVE);
        User user = user();
        user.withdraw("withdrawn", LocalDateTime.now());
        when(repository.findById(1L)).thenReturn(Optional.of(user));
        assertCode(() -> service.findActive(1L), SecurityExceptionCode.USER_NOT_ACTIVE);
    }

    @Test
    void inactiveUserWithMatchingCredentialsCannotLogin() {
        User user = user();
        ReflectionTestUtils.setField(user, "status",
                com.dameokja.backend.user.domain.UserStatus.WITHDRAWN);
        when(repository.findIdByLoginId("user")).thenReturn(Optional.of(1L));
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        assertCode(() -> service.authenticate("user", "password"),
                SecurityExceptionCode.USER_NOT_ACTIVE);
    }

    private void assertCode(Runnable action, ExceptionCode code) {
        assertThatThrownBy(action::run).isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getExceptionCode()).isEqualTo(code);
    }
}
