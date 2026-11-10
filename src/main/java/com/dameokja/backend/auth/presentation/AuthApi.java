package com.dameokja.backend.auth.presentation;

import static com.dameokja.backend.auth.presentation.AuthApiExamples.LOGIN_REQUEST;
import static com.dameokja.backend.auth.presentation.AuthApiExamples.LOGIN_RESPONSE;
import static com.dameokja.backend.auth.presentation.AuthApiExamples.LOGOUT_RESPONSE;
import static com.dameokja.backend.auth.presentation.AuthApiExamples.REFRESH_RESPONSE;

import com.dameokja.backend.auth.presentation.AuthController.LoginData;
import com.dameokja.backend.auth.presentation.AuthController.RenewalData;
import com.dameokja.backend.global.response.SuccessResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Tag(name = "인증", description = "로그인·토큰 갱신·로그아웃 API")
public interface AuthApi {

    @Operation(
            summary = "로그인",
            description = "로그인 아이디와 비밀번호로 인증하고 액세스·리프레시 토큰을 쿠키로 발급합니다. "
                    + "로그인 성공 시 가입 대기용 registrationToken 쿠키를 삭제합니다. "
                    + "유효한 CSRF 토큰이 없다면 GET /api/v1/auth/csrf로 먼저 발급받아야 합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = LOGIN_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 (GLOBAL-400-001)"),
            @ApiResponse(
                    responseCode = "401",
                    description = "아이디 또는 비밀번호 불일치 (AUTH-401-001, 아이디 존재 여부를 구분하지 않음)"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰이 없거나 올바르지 않음 (COMMON-403-CSRF-001), "
                            + "탈퇴한 회원 (USER-403-001)"
            ),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    SuccessResponse<LoginData> login(
            @RequestBody(
                    required = true,
                    description = "로그인 아이디와 비밀번호",
                    content = @Content(
                            schema = @Schema(implementation = LoginRequest.class),
                            examples = @ExampleObject(name = "로그인 요청", value = LOGIN_REQUEST)
                    )
            )
            LoginRequest credentials,
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(hidden = true) HttpServletResponse response);

    @Operation(
            summary = "인증정보 갱신",
            description = "리프레시 토큰 쿠키로 액세스·리프레시 토큰을 재발급(회전)합니다. "
                    + "토큰 누락·무효 또는 회원 탈퇴·삭제로 갱신이 거절되면 인증 쿠키를 삭제합니다. 서버 장애 시에는 유지합니다. "
                    + "유효한 CSRF 토큰이 없다면 GET /api/v1/auth/csrf로 먼저 발급받아야 합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "인증정보 갱신 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = REFRESH_RESPONSE)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "리프레시 토큰이 없거나 유효하지 않음 (AUTH-401-003, AUTH-401-002)"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰이 없거나 올바르지 않음 (COMMON-403-CSRF-001), "
                            + "탈퇴한 회원 (USER-403-001)"
            ),
            @ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음 (USER-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    SuccessResponse<RenewalData> refresh(
            @Parameter(
                    name = "refreshToken",
                    in = ParameterIn.COOKIE,
                    description = "리프레시 토큰 쿠키",
                    required = false
            )
            String refreshToken,
            @Parameter(hidden = true) HttpServletResponse response);

    @Operation(
            summary = "로그아웃",
            description = "액세스 토큰의 누락·만료·유효성과 무관하게 리프레시 세션을 폐기하고 인증 쿠키를 제거합니다. "
                    + "리프레시 토큰이 없거나 무효·만료되어도 쿠키를 정리하고 성공을 반환합니다. "
                    + "유효한 CSRF 토큰이 없다면 GET /api/v1/auth/csrf로 먼저 발급받아야 합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그아웃 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = LOGOUT_RESPONSE)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰이 없거나 올바르지 않음 (COMMON-403-CSRF-001)"
            ),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    SuccessResponse<Void> logout(
            @Parameter(
                    name = "refreshToken",
                    in = ParameterIn.COOKIE,
                    description = "리프레시 토큰 쿠키",
                    required = false
            )
            String refreshToken,
            @Parameter(hidden = true) HttpServletRequest request,
            @Parameter(hidden = true) HttpServletResponse response);
}
