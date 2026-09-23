package com.dameokja.backend.user.integration;

import com.dameokja.backend.auth.application.AuthService;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.SignupResult;
import com.dameokja.backend.user.application.UserSignupService;
import com.dameokja.backend.user.domain.User;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

class UserSignupIntegrationTest extends ServiceIntegrationTest {
    @Autowired UserSignupService signup;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean JwtProvider jwtProvider;

    @Test
    void persistsHashedCredentialsAndPersonalRefrigeratorWithUsableTokens() {
        String password = " A1" + "a".repeat(69);
        SignupResult result = signup.signup("login1", password, null);
        User user = userRepository.findById(result.tokenPair().userId()).orElseThrow();
        assertThat(user.getLoginId()).isEqualTo("login1");
        assertThat(user.getNickname()).isEqualTo("login1");
        assertThat(user.getProfileImageKey()).isEqualTo("profiles/default.png");
        assertThat(user.getPasswordHash()).isNotEqualTo(password);
        assertThat(encoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(result.activeRefrigeratorIds()).containsExactly(
                refrigeratorRepository.findAll().getFirst().getId());
        assertThat(rows("refrigerator_members")).isEqualTo(1);
        assertThat(jwtProvider.parseAccessTokenPayload(result.tokenPair().accessToken()).userId())
                .isEqualTo(user.getId());
        assertThat(auth.refresh(result.tokenPair().refreshToken()).userId()).isEqualTo(user.getId());
    }

    @Test
    void storesCompleteHangulLoginId() {
        SignupResult result = signup.signup("한글아이디1", "pass1234", null);
        User user = userRepository.findById(result.tokenPair().userId()).orElseThrow();
        assertThat(user.getLoginId()).isEqualTo("한글아이디1");
    }

    @ParameterizedTest
    @CsvSource({"login2,별명, NICKNAME_DUPLICATE", "login1,다른별명, LOGIN_ID_DUPLICATE"})
    void duplicateSignupDoesNotCreateAdditionalRows(String loginId, String nickname,
            UserExceptionCode expected) {
        signup.signup("login1", "pass1234", "별명");
        assertThatThrownBy(() -> signup.signup(loginId, "pass1234", nickname))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getExceptionCode()).isEqualTo(expected));
        assertThat(rows("users")).isEqualTo(1);
        assertThat(rows("refrigerators")).isEqualTo(1);
        assertThat(rows("refrigerator_members")).isEqualTo(1);
    }

    @Test
    void rollsBackUserAndRefrigeratorWhenAutomaticLoginFails() {
        IllegalStateException failure = new IllegalStateException("token issuance failed");
        doAnswer(invocation -> {
            assertThat(rows("users")).isEqualTo(1);
            assertThat(rows("refrigerators")).isEqualTo(1);
            assertThat(rows("refrigerator_members")).isEqualTo(1);
            throw failure;
        }).when(jwtProvider).createRefreshToken(anyLong(), any(UUID.class));
        assertThatThrownBy(() -> signup.signup("login1", "pass1234", null)).isSameAs(failure);
        assertEmptyDatabase();
    }
}
