package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserSignupServiceTest {
    private final UserRegistrationService registration = mock(UserRegistrationService.class);
    private final AuthService auth = mock(AuthService.class);
    private final UserSignupService signup = new UserSignupService(registration, auth);

    @Test
    void normalizesOnlyLoginIdAndReturnsCreatedRefrigeratorAfterLogin() {
        var command = new RegisterUserCommand(null, null, "한User1", " pass1234 ");
        var tokens = new TokenPair("access", "refresh", 7L);
        when(registration.register(command)).thenReturn(new RegistrationResult(7L, 9L));
        when(auth.login("한User1", command.password())).thenReturn(tokens);
        var result = signup.signup(" 한\tUser\n1\u3000\u00a0", command.password(), null);
        assertThat(result.tokenPair()).isEqualTo(tokens);
        assertThat(result.activeRefrigeratorIds()).containsExactly(9L);
        var order = inOrder(registration, auth);
        order.verify(registration).register(command);
        order.verify(auth).login("한User1", command.password());
        assertThat(command.toString()).doesNotContain(command.password());
    }

    @Test
    void doesNotLoginWhenRegistrationFails() {
        var command = new RegisterUserCommand(null, null, null, null);
        var failure = new IllegalArgumentException("registration rejected");
        when(registration.register(command)).thenThrow(failure);
        assertThatThrownBy(() -> signup.signup(null, null, null)).isSameAs(failure);
        verifyNoInteractions(auth);
    }
}
