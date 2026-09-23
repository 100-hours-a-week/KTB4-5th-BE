package com.dameokja.backend.user.presentation;

import static com.dameokja.backend.user.presentation.UserApiExamples.SIGNUP_REQUEST;
import static com.dameokja.backend.user.presentation.UserApiExamples.SIGNUP_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
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

@Tag(name = "회원", description = "회원 API")
public interface UserApi {

    @Operation(
            summary = "로컬 회원가입",
            description = "아이디·비밀번호·닉네임으로 회원가입하고, 개인 냉장고 생성과 자동 로그인까지 한 번에 처리합니다. "
                    + "닉네임을 비우면 아이디로 대체합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "회원가입 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = SIGNUP_RESPONSE)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 형식 또는 아이디·닉네임·비밀번호 입력값 오류 "
                            + "(GLOBAL-400-001, USER-400-001~002)"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰이 없거나 올바르지 않음 (COMMON-403-CSRF-001)"
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 사용 중인 닉네임 또는 아이디 (USER-409-001~002)"
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "사용할 수 없는 닉네임 또는 아이디 (USER-422-001~002)"
            ),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<SignupResponse>> signup(
            @RequestBody(
                    required = true,
                    description = "아이디·닉네임은 완성형 한글·영문·숫자 2~10자(공백 제거), 비밀번호는 영문·숫자를 포함한 8자 이상입니다.",
                    content = @Content(
                            schema = @Schema(implementation = SignupRequest.class),
                            examples = @ExampleObject(name = "회원가입 요청", value = SIGNUP_REQUEST)
                    )
            )
            SignupRequest request,
            @Parameter(hidden = true) HttpServletRequest httpRequest,
            @Parameter(hidden = true) HttpServletResponse httpResponse);
}
