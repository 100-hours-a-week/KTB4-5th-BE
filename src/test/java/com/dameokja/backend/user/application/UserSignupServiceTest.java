package com.dameokja.backend.user.application;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.auth.application.TokenPair;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserSignupServiceTest {
    private final UserRegistrationService registration = mock(UserRegistrationService.class);
    private final AuthService auth = mock(AuthService.class);
    private final RefrigeratorAccessService refrigerators = mock(RefrigeratorAccessService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ProhibitedWordChecker prohibitedWords = mock(ProhibitedWordChecker.class);
    private final UserSignupService signup = new UserSignupService(registration, auth,
            refrigerators, encoder, new LoginIdPolicy(new NicknameAndLoginIdValidator(prohibitedWords)),
            new PasswordPolicy());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "\u3000", "\u00a0"})
    void defaultsBlankNicknameAndNormalizesLoginId(String nickname) {
        TokenPair tokens = new TokenPair("access", "refresh", 7L);
        when(encoder.encode("pass1234")).thenReturn("hash");
        when(registration.register(any())).thenReturn(new UserProfile(7L, "한User1", "default"));
        when(refrigerators.findActiveRefrigeratorIds(7L)).thenReturn(List.of(9L));
        when(auth.login("한User1", "pass1234")).thenReturn(tokens);
        SignupResult result = signup.signup(" 한\tUser\n1\u3000\u00a0", "pass1234", nickname);
        assertThat(result.tokenPair()).isEqualTo(tokens);
        assertThat(result.activeRefrigeratorIds()).containsExactly(9L);
        var order = inOrder(registration, refrigerators, auth);
        order.verify(registration).register(new RegisterUserCommand("한User1", null, "한User1", "hash"));
        order.verify(refrigerators).findActiveRefrigeratorIds(7L);
        order.verify(auth).login("한User1", "pass1234");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "\u3000"})
    void rejectsMissingLoginIdBeforeSaving(String loginId) {
        assertRejected(loginId, "pass1234", UserExceptionCode.LOGIN_ID_REQUIRED);
    }

    @ParameterizedTest
    @CsvSource({"A,LOGIN_ID_LENGTH_INVALID", "Abcdefgh123,LOGIN_ID_LENGTH_INVALID",
            "Ab_12,LOGIN_ID_FORMAT_INVALID", "漢字,LOGIN_ID_FORMAT_INVALID"})
    void rejectsInvalidLoginId(String loginId, UserExceptionCode expected) {
        assertRejected(loginId, "pass1234", expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"        "})
    void rejectsMissingPassword(String password) {
        assertRejected("user1", password, UserExceptionCode.PASSWORD_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pass123", "password", "12345678", "한글비밀번호12"})
    void rejectsInvalidPassword(String password) {
        assertRejected("user1", password, UserExceptionCode.PASSWORD_FORMAT_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "한"})
    void rejectsPasswordOverBcryptByteLimit(String character) {
        String suffix = character.equals("a") ? "a".repeat(71) : "한".repeat(23) + "aa";
        assertRejected("user1", "A1" + suffix, UserExceptionCode.PASSWORD_FORMAT_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pass1234", " pass1234 ", "aA1!가나다라"})
    void preservesPasswordAndExplicitNickname(String password) {
        when(encoder.encode(password)).thenReturn("hash");
        when(registration.register(any())).thenReturn(new UserProfile(7L, "별명", "default"));
        when(refrigerators.findActiveRefrigeratorIds(7L)).thenReturn(List.of(9L));
        signup.signup("user1", password, "별명");
        verify(encoder).encode(password);
        verify(registration).register(new RegisterUserCommand("별명", null, "user1", "hash"));
        verify(auth).login("user1", password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1", "Abcdef1234"})
    void acceptsLoginIdLengthBoundariesAndSeventyTwoBytePassword(String loginId) {
        String password = "A1" + "a".repeat(70);
        when(registration.register(any())).thenReturn(new UserProfile(7L, loginId, "default"));
        when(refrigerators.findActiveRefrigeratorIds(7L)).thenReturn(List.of(9L));
        signup.signup(loginId, password, null);
        verify(encoder).encode(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "한"})
    void acceptsSeventyTwoBytesIncludingMultibyteCharacters(String character) {
        String suffix = character.equals("a") ? "a".repeat(70) : "한".repeat(23) + "a";
        when(registration.register(any())).thenReturn(new UserProfile(7L, "user1", "default"));
        when(refrigerators.findActiveRefrigeratorIds(7L)).thenReturn(List.of(9L));
        signup.signup("user1", "A1" + suffix, null);
        verify(encoder).encode("A1" + suffix);
    }

    @ParameterizedTest
    @ValueSource(strings = {"registration", "refrigerators"})
    void doesNotIssueTokensWhenEarlierStepFails(String stage) {
        IllegalStateException failure = new IllegalStateException("storage failure");
        if (stage.equals("registration")) {
            when(registration.register(any())).thenThrow(failure);
        } else {
            when(registration.register(any())).thenReturn(new UserProfile(7L, "user1", "default"));
            when(refrigerators.findActiveRefrigeratorIds(7L)).thenThrow(failure);
        }
        assertThatThrownBy(() -> signup.signup("user1", "pass1234", null)).isSameAs(failure);
        verifyNoInteractions(auth);
    }

    private void assertRejected(String loginId, String password, UserExceptionCode expected) {
        assertThatThrownBy(() -> signup.signup(loginId, password, null))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getExceptionCode()).isEqualTo(expected));
        verifyNoInteractions(registration, auth, refrigerators, encoder);
    }
}
