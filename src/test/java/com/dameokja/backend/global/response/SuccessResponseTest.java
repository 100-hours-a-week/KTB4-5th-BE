package com.dameokja.backend.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.auth.presentation.response.AuthSuccessCode;
import com.dameokja.backend.user.presentation.response.UserSuccessCode;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SuccessResponseTest {

    @ParameterizedTest
    @MethodSource("successCodes")
    void preservesExistingCodeMessageAndData(SuccessCode successCode, String code, String message) {
        String data = "response-data";

        assertThat(SuccessResponse.of(successCode, data))
                .isEqualTo(new SuccessResponse<>(code, message, data));
        assertThat(SuccessResponse.of(successCode, null))
                .isEqualTo(new SuccessResponse<>(code, message, null));
    }

    private static Stream<Arguments> successCodes() {
        return Stream.of(
                Arguments.of(AuthSuccessCode.LOGIN, "AUTH-200-001", "로그인 성공"),
                Arguments.of(AuthSuccessCode.LOGOUT, "AUTH-200-005", "로그아웃 성공"),
                Arguments.of(AuthSuccessCode.REFRESH, "AUTH-200-006", "인증정보 갱신 성공"),
                Arguments.of(UserSuccessCode.SIGNUP, "USER-201-001", "회원가입 성공"));
    }
}
