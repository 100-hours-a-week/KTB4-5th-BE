package com.dameokja.backend.user.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.user.presentation.request.OAuthSignupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

@Tag(name = "회원")
public interface OAuthSignupApi {
    @Operation(summary = "소셜 회원가입 완료", description = "registrationToken 쿠키의 서버 소셜 정보로 가입하고 자동 로그인합니다. CSRF 쿠키와 헤더가 필수입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공 (USER-201-002)",
                    content = @Content(examples = @ExampleObject(value = OAuthSignupApiExamples.RESPONSE))),
            @ApiResponse(responseCode = "400", description = "입력 형식 오류 (GLOBAL-400-001), 가입 인증 무효 (USER-400-001)"),
            @ApiResponse(responseCode = "403", description = "CSRF 검증 실패 (COMMON-403-CSRF-001)"),
            @ApiResponse(responseCode = "409", description = "닉네임 중복 (USER-409-001), 소셜 계정 중복 (USER-409-004)"),
            @ApiResponse(responseCode = "422", description = "사용할 수 없는 닉네임 (USER-422-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<SignupResponse>> signup(
            @Parameter(hidden = true) String token,
            @RequestBody(required = true, description = "닉네임은 공백을 제거한 한글·영문·숫자 2~10자입니다. 알림 설정은 필수입니다.",
                    content = @Content(schema = @Schema(implementation = OAuthSignupRequest.class),
                            examples = @ExampleObject(value = OAuthSignupApiExamples.REQUEST))) OAuthSignupRequest request,
            @Parameter(hidden = true) HttpServletRequest httpRequest,
            @Parameter(hidden = true) HttpServletResponse httpResponse);
}
