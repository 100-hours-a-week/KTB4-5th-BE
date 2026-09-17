package com.dameokja.backend.global.exception;

import com.dameokja.backend.user.domain.*;
import com.dameokja.backend.refrigerator.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class DomainExceptionResponseTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new UserException(UserExceptionCode.NICKNAME_REQUIRED), 400, "USER-400-001", "닉네임을 입력해 주세요."),
                Arguments.of(new UserException(UserExceptionCode.NICKNAME_LENGTH_INVALID), 400, "USER-400-002", "닉네임은 2~10자로 입력해 주세요."),
                Arguments.of(new UserException(UserExceptionCode.NICKNAME_FORMAT_INVALID), 400, "USER-400-003", "닉네임은 영문과 숫자를 각각 하나 이상 포함해야 합니다."),
                Arguments.of(new UserException(UserExceptionCode.NICKNAME_PROHIBITED), 400, "USER-400-004", "사용할 수 없는 닉네임입니다."),
                Arguments.of(new UserException(UserExceptionCode.PROFILE_IMAGE_INVALID), 400, "USER-400-005", "프로필 이미지 변경 요청이 올바르지 않습니다."),
                Arguments.of(new UserException(UserExceptionCode.USER_NOT_ACTIVE), 403, "USER-403-002", "탈퇴한 회원은 이용할 수 없습니다."),
                Arguments.of(new UserException(UserExceptionCode.USER_NOT_FOUND), 404, "USER-404-001", "회원을 찾을 수 없습니다."),
                Arguments.of(new UserException(UserExceptionCode.NICKNAME_DUPLICATE), 409, "USER-409-001", "이미 사용 중인 닉네임입니다."),
                Arguments.of(new UserException(UserExceptionCode.LOGIN_ID_DUPLICATE), 409, "USER-409-002", "이미 사용 중인 로그인 아이디입니다."),
                Arguments.of(new RefrigeratorException(RefrigeratorExceptionCode.INVALID_INCREMENT), 400, "REFRIGERATOR-400-001", "만료 재고 증가량은 1 이상이어야 합니다."),
                Arguments.of(new RefrigeratorException(RefrigeratorExceptionCode.ACCESS_DENIED), 403, "REFRIGERATOR-403-001", "해당 냉장고에 접근할 수 없습니다."),
                Arguments.of(new RefrigeratorException(RefrigeratorExceptionCode.REFRIGERATOR_NOT_FOUND), 404, "REFRIGERATOR-404-001", "냉장고를 찾을 수 없습니다."),
                Arguments.of(new RefrigeratorException(RefrigeratorExceptionCode.REFRIGERATOR_DELETED), 410, "REFRIGERATOR-410-001", "삭제된 냉장고입니다."));
    }

    @ParameterizedTest @MethodSource("errors")
    void returnsAgreedStatusCodeAndUserFacingMessage(CustomException error, int status, String code, String message) {
        var response = handler.handleCustomException(error);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).isEqualTo(message);
        assertThat(response.getBody().errors()).isNull();
        assertThat(response.getBody().retryable()).isNull();
    }

    @Test void unknownDatabaseFailuresRemainInternalErrorsWithoutLeakingDetails() {
        var response = handler.handleDataIntegrityViolation(new DataIntegrityViolationException("secret SQL and credentials"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("GLOBAL-500-001");
        assertThat(response.getBody().message()).isEqualTo("서버에서 요청을 처리하지 못했습니다.");
        assertThat(response.getBody().message()).doesNotContain("secret", "SQL", "credentials");
    }

    @Test void unexpectedFailuresRemainInternalErrors() {
        var response = handler.handleUnexpectedException(new IllegalStateException("internal details"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("GLOBAL-500-001");
        assertThat(response.getBody().message()).isEqualTo("서버에서 요청을 처리하지 못했습니다.");
    }
}
