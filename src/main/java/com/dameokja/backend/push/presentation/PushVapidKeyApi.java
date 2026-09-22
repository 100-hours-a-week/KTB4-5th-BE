package com.dameokja.backend.push.presentation;

import static com.dameokja.backend.push.presentation.PushVapidKeyApiExamples.VAPID_KEY_RESPONSE;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.push.presentation.response.PushVapidKeyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "푸시", description = "Web Push 구독 등록·해제 API")
public interface PushVapidKeyApi {

    @Operation(
            summary = "VAPID 공개키 조회",
            description = "브라우저가 푸시 구독을 생성할 때 필요한 서버 VAPID 공개키와 그 버전을 반환합니다. "
                    + "로그인 여부와 관계없이 호출할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "VAPID 공개키 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = VAPID_KEY_RESPONSE)
                    )
            ),
            @ApiResponse(responseCode = "500", description = "서버 오류 (GLOBAL-500-001)")
    })
    SuccessResponse<PushVapidKeyResponse> vapidPublicKey();
}
