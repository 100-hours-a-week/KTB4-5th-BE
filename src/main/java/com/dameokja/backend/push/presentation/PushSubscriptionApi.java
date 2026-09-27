package com.dameokja.backend.push.presentation;

import static com.dameokja.backend.push.presentation.PushSubscriptionApiExamples.SUBSCRIBE_CREATED_RESPONSE;
import static com.dameokja.backend.push.presentation.PushSubscriptionApiExamples.SUBSCRIBE_REQUEST;
import static com.dameokja.backend.push.presentation.PushSubscriptionApiExamples.SUBSCRIBE_RENEWED_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.push.presentation.request.PushSubscriptionRequest;
import com.dameokja.backend.push.presentation.response.PushSubscriptionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "푸시", description = "Web Push 구독 등록·해제 API")
public interface PushSubscriptionApi {

    @Operation(
            summary = "푸시 구독 등록·갱신",
            description = "브라우저에서 생성한 Web Push 구독(endpoint, p256dh, auth)을 등록합니다. "
                    + "이미 등록된 endpoint면 본인 구독일 때만 갱신하고, 다른 계정의 구독이면 409를 반환합니다. "
                    + "auth는 서버에 암호화되어 저장되며 응답에 포함되지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "신규 구독 생성 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = SUBSCRIBE_CREATED_RESPONSE)
                    )
            ),
            @ApiResponse(
                    responseCode = "200",
                    description = "기존 구독 갱신 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = SUBSCRIBE_RENEWED_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 (GLOBAL-400-001)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰이 없거나 올바르지 않음 (COMMON-403-CSRF-001)"
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "다른 계정이 이미 사용 중인 구독 (PUSH-409-001)"
            ),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<SuccessResponse<PushSubscriptionResponse>> register(
            @Parameter(hidden = true) Long userId,
            @RequestBody(
                    required = true,
                    description = "브라우저 PushSubscription 정보",
                    content = @Content(
                            schema = @Schema(implementation = PushSubscriptionRequest.class),
                            examples = @ExampleObject(name = "구독 등록 요청", value = SUBSCRIBE_REQUEST)
                    )
            )
            PushSubscriptionRequest request);

    @Operation(
            summary = "푸시 구독 해제",
            description = "본인 소유 구독을 비활성화합니다. 이미 해제된 구독을 다시 요청해도 상태 변경 없이 "
                    + "204를 반환합니다(멱등). 이미 발송된 푸시는 회수되지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "구독 해제 성공(이미 해제된 경우 포함)"),
            @ApiResponse(responseCode = "401", description = "로그인이 필요함 (GLOBAL-401-001)"),
            @ApiResponse(
                    responseCode = "403",
                    description = "본인 구독이 아님 (PUSH-403-001), CSRF 토큰이 없거나 올바르지 않음 "
                            + "(COMMON-403-CSRF-001)"
            ),
            @ApiResponse(responseCode = "404", description = "구독을 찾을 수 없음 (PUSH-404-001)"),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    ResponseEntity<Void> unregister(
            @Parameter(hidden = true) Long userId,
            @Parameter(description = "해제할 구독 ID") Long subscriptionId);
}
