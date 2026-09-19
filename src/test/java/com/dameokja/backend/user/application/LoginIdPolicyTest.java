package com.dameokja.backend.user.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.user.domain.UserExceptionCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginIdPolicyTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"한글", "Abcdef", "12345", "한A1", "ㄱㄴ", "ㅏㅑ", "A", "Abcdefgh123", "시발"})
    void acceptsAllowedCharactersAndAbsentSocialLoginId(String loginId) {
        assertThatCode(() -> LoginIdPolicy.validate(loginId)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " id", "id ", "i d", "a_b", "a-b", "a@b", "漢字", "é", "😀"})
    void rejectsEmptyOrDisallowedCharacters(String loginId) {
        assertThatThrownBy(() -> LoginIdPolicy.validate(loginId))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getExceptionCode())
                                .isEqualTo(UserExceptionCode.LOGIN_ID_FORMAT_INVALID));
    }
}
