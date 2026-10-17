package com.dameokja.backend.user.presentation;

import static com.dameokja.backend.user.presentation.UserApiExamples.NICKNAME_VALIDATION_AVAILABLE;
import static com.dameokja.backend.user.presentation.UserApiExamples.NICKNAME_VALIDATION_DUPLICATE;
import static com.dameokja.backend.user.presentation.UserApiExamples.NICKNAME_VALIDATION_INVALID_FORMAT;
import static com.dameokja.backend.user.presentation.UserApiExamples.NICKNAME_VALIDATION_PROHIBITED;
import static com.dameokja.backend.user.presentation.UserApiExamples.NICKNAME_VALIDATION_REQUEST;
import static com.dameokja.backend.user.presentation.UserApiExamples.SIGNUP_REQUEST;
import static com.dameokja.backend.user.presentation.UserApiExamples.SIGNUP_RESPONSE;
import static com.dameokja.backend.user.presentation.UserApiExamples.PROFILE_RESPONSE;
import static com.dameokja.backend.user.presentation.UserApiExamples.PROFILE_UPDATE_REQUEST;
import static com.dameokja.backend.user.presentation.UserApiExamples.PROFILE_UPDATE_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.user.presentation.request.NicknameValidationRequest;
import com.dameokja.backend.user.presentation.response.NicknameValidationResponse;
import com.dameokja.backend.user.presentation.response.UserProfileResponse;
import com.dameokja.backend.user.presentation.request.UserProfileUpdateRequest;
import com.dameokja.backend.user.presentation.response.UserProfileUpdateResponse;
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

    @Operation(summary = "내 정보 조회", description = "accessToken 쿠키로 인증한 회원의 프로필·활성 냉장고·만든 요리 수를 조회합니다. "
            + "email은 저장된 카카오 이메일이며, 연결된 카카오 계정이나 이메일이 없으면 null입니다. "
            + "profileImageUrl은 S3 임시 조회 URL이며 유효기간이 지나면 이 API를 다시 조회해야 합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원정보 조회 성공",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = PROFILE_RESPONSE))),
            @ApiResponse(responseCode = "401", description = "인증 정보 없음 또는 유효하지 않음 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "탈퇴한 회원 (USER-403-001)"),
            @ApiResponse(responseCode = "404", description = "회원을 찾을 수 없음 (USER-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<UserProfileResponse>> getProfile(@Parameter(hidden = true) Long userId);

    @Operation(summary = "회원정보 수정", description = "인증된 본인의 닉네임·프로필 사진을 저장합니다. "
            + "nickname은 필수이며 공백 제거 후 한글·영문·숫자 2~10자, 금칙어·본인 제외 중복을 검사합니다. "
            + "profileImage는 PROFILE 업로드 객체 키입니다. 생략하면 유지, null이면 기본 사진으로 초기화합니다. "
            + "사진을 변경하려면 본인에게 발급된 유효한 업로드 키와 실제 S3 객체가 필요합니다. "
            + "accessToken 쿠키와 XSRF-TOKEN 쿠키·X-XSRF-TOKEN 헤더가 필요합니다. "
            + "성공 응답의 profileImageUrl은 기본 사진에도 발급하는 S3 임시 조회 URL입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원정보 수정 성공 (USER-200-004)",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = PROFILE_UPDATE_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "필수값 누락·형식 오류·유효하지 않은 프로필 이미지 (GLOBAL-400-001)"),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001)"),
            @ApiResponse(responseCode = "403", description = "CSRF 검증 실패 (COMMON-403-CSRF-001) 또는 탈퇴 회원 (USER-403-001)"),
            @ApiResponse(responseCode = "404", description = "회원 없음 (USER-404-001)"),
            @ApiResponse(responseCode = "409", description = "이미 사용 중인 닉네임 (USER-409-001)"),
            @ApiResponse(responseCode = "422", description = "닉네임 금칙어 (USER-422-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<UserProfileUpdateResponse>> updateProfile(@Parameter(hidden = true) Long userId,
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UserProfileUpdateRequest.class),
                    examples = @ExampleObject(value = PROFILE_UPDATE_REQUEST))) UserProfileUpdateRequest request);

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
                            + "(GLOBAL-400-001)"
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
                    description = "아이디·닉네임은 완성형 한글·영문·숫자 2~10자(공백 제거), 비밀번호는 영문·숫자만으로 각각 1자 이상 포함한 8~72자입니다.",
                    content = @Content(
                            schema = @Schema(implementation = SignupRequest.class),
                            examples = @ExampleObject(name = "회원가입 요청", value = SIGNUP_REQUEST)
                    )
            )
            SignupRequest request,
            @Parameter(hidden = true) HttpServletRequest httpRequest,
            @Parameter(hidden = true) HttpServletResponse httpResponse);

    @Operation(
            summary = "닉네임 검사",
            description = "공백을 제거한 닉네임의 형식·금칙어·중복을 차례로 검사합니다. "
                    + "사용할 수 없는 닉네임도 200으로 응답하고 data.valid·data.reason으로 결과를 알립니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "검사 완료 (USER-200-001)",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "사용 가능", value = NICKNAME_VALIDATION_AVAILABLE),
                                    @ExampleObject(name = "형식 위반", value = NICKNAME_VALIDATION_INVALID_FORMAT),
                                    @ExampleObject(name = "금칙어", value = NICKNAME_VALIDATION_PROHIBITED),
                                    @ExampleObject(name = "중복", value = NICKNAME_VALIDATION_DUPLICATE)
                            }
                    )
            ),
            @ApiResponse(responseCode = "400", description = "nickname 누락 또는 요청 형식 오류 (GLOBAL-400-001)"),
            @ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰이 없거나 올바르지 않음 (COMMON-403-CSRF-001)"
            ),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<NicknameValidationResponse>> validateNickname(
            @RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = NicknameValidationRequest.class),
                            examples = @ExampleObject(name = "닉네임 검사 요청", value = NICKNAME_VALIDATION_REQUEST)
                    )
            )
            NicknameValidationRequest request);
}
