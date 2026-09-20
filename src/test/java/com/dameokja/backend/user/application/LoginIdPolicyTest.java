package com.dameokja.backend.user.application;

import com.dameokja.backend.global.moderation.ProhibitedWordChecker;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginIdPolicyTest {
    private final ProhibitedWordChecker prohibitedWordChecker = mock(ProhibitedWordChecker.class);
    private final LoginIdPolicy loginIdPolicy =
            new LoginIdPolicy(new NicknameAndLoginIdValidator(prohibitedWordChecker));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingLoginId(String loginId) {
        assertRejected(loginId, UserExceptionCode.LOGIN_ID_REQUIRED);
    }

    @ParameterizedTest
    @CsvSource({
            "A, LOGIN_ID_LENGTH_INVALID",
            "Abcdefgh123, LOGIN_ID_LENGTH_INVALID",
            "Ab_12, LOGIN_ID_FORMAT_INVALID",
            "漢字, LOGIN_ID_FORMAT_INVALID",
            "éé, LOGIN_ID_FORMAT_INVALID",
            "' Ab12', LOGIN_ID_FORMAT_INVALID",
            "'Ab12 ', LOGIN_ID_FORMAT_INVALID",
            "'Ab 12', LOGIN_ID_FORMAT_INVALID"
    })
    void rejectsInvalidLoginIdBeforeCheckingProhibitedWords(
            String loginId, UserExceptionCode expectedExceptionCode) {
        assertRejected(loginId, expectedExceptionCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "A1", "Abcdef1234", "slang1", "한글", "한글12", "Abcdef", "12345", "ㄱㄴ", "ㅏㅑ", "한A1"
    })
    void acceptsValidLoginId(String loginId) {
        assertThatCode(() -> loginIdPolicy.validate(loginId)).doesNotThrowAnyException();
    }

    @Test
    void convertsProhibitedWordMatchToUserError() {
        when(prohibitedWordChecker.containsProhibitedWord("Bad1")).thenReturn(true);
        assertRejected("Bad1", UserExceptionCode.LOGIN_ID_PROHIBITED);
    }

    private void assertRejected(String loginId, UserExceptionCode expectedExceptionCode) {
        assertThatThrownBy(() -> loginIdPolicy.validate(loginId))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(expectedExceptionCode));
    }
}
