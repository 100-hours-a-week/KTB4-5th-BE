package com.dameokja.backend.global.exception;

import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.user.domain.UserExceptionCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionResponseTest {
    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new CustomException(UserExceptionCode.NICKNAME_REQUIRED),
                        400, "USER-400-001", "닉네임을 입력해 주세요."),
                Arguments.of(new CustomException(UserExceptionCode.NICKNAME_LENGTH_INVALID),
                        400, "USER-400-002", "닉네임은 2~10자로 입력해 주세요."),
                Arguments.of(new CustomException(UserExceptionCode.NICKNAME_FORMAT_INVALID),
                        400, "USER-400-003", "닉네임은 한글, 영문, 숫자만 사용할 수 있습니다."),
                Arguments.of(new CustomException(UserExceptionCode.NICKNAME_PROHIBITED),
                        422, "USER-422-001", "사용할 수 없는 닉네임입니다."),
                Arguments.of(new CustomException(UserExceptionCode.LOGIN_ID_FORMAT_INVALID),
                        400, "USER-400-006", "로그인 아이디는 한글, 영문, 숫자만 사용할 수 있습니다."),
                Arguments.of(new CustomException(UserExceptionCode.LOGIN_ID_REQUIRED),
                        400, "USER-400-007", "로그인 아이디를 입력해 주세요."),
                Arguments.of(new CustomException(UserExceptionCode.PASSWORD_REQUIRED),
                        400, "USER-400-008", "비밀번호를 입력해 주세요."),
                Arguments.of(new CustomException(UserExceptionCode.USER_NOT_ACTIVE),
                        403, "USER-403-002", "탈퇴한 회원은 이용할 수 없습니다."),
                Arguments.of(new CustomException(UserExceptionCode.USER_NOT_FOUND),
                        404, "USER-404-001", "회원을 찾을 수 없습니다."),
                Arguments.of(new CustomException(UserExceptionCode.NICKNAME_DUPLICATE),
                        409, "USER-409-001", "이미 사용 중인 닉네임입니다."),
                Arguments.of(new CustomException(UserExceptionCode.LOGIN_ID_DUPLICATE),
                        409, "USER-409-002", "이미 사용 중인 로그인 아이디입니다."),
                Arguments.of(new CustomException(RefrigeratorExceptionCode.ACCESS_DENIED),
                        403, "REFRIGERATOR-403-001", "해당 냉장고에 접근할 수 없습니다."),
                Arguments.of(new CustomException(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND),
                        404, "REFRIGERATOR-404-001", "냉장고를 찾을 수 없습니다."),
                Arguments.of(new CustomException(RefrigeratorExceptionCode.REFRIGERATOR_DELETED),
                        410, "REFRIGERATOR-410-001", "삭제된 냉장고입니다."));
    }

    static Stream<Arguments> loginIdErrors() {
        return Stream.of(
                Arguments.of(new CustomException(UserExceptionCode.LOGIN_ID_LENGTH_INVALID),
                        400, "USER-400-009", "로그인 아이디는 2~10자로 입력해 주세요."),
                Arguments.of(new CustomException(UserExceptionCode.LOGIN_ID_PROHIBITED),
                        422, "USER-422-002", "사용할 수 없는 로그인 아이디입니다."));
    }

    @ParameterizedTest
    @MethodSource({"errors", "loginIdErrors"})
    void returnsAgreedStatusCodeAndUserFacingMessage(
            CustomException error, int status, String code, String message) {
        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleCustomException(error);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).isEqualTo(message);
        assertThat(response.getBody().errors()).isNull();
        assertThat(response.getBody().retryable()).isNull();
    }

    @Test
    void unknownDatabaseFailuresRemainInternalErrorsWithoutLeakingDetails() {
        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("secret SQL and credentials"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("GLOBAL-500-001");
        assertThat(response.getBody().message()).isEqualTo("서버에서 요청을 처리하지 못했습니다.");
        assertThat(response.getBody().message()).doesNotContain("secret", "SQL", "credentials");
    }

    @Test
    void unexpectedFailuresRemainInternalErrors() {
        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleUnexpectedException(
                new IllegalStateException("internal details"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("GLOBAL-500-001");
        assertThat(response.getBody().message()).isEqualTo("서버에서 요청을 처리하지 못했습니다.");
    }
}
