package com.dameokja.backend.image.presentation;

import com.dameokja.backend.global.exception.ErrorResponse;
import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.image.presentation.request.ImagePresignRequest;
import com.dameokja.backend.image.presentation.response.ImagePresignResponse;
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

@Tag(name = "이미지", description = "이미지 직접 업로드 URL 발급")
public interface ImageApi {
    @Operation(summary = "이미지 업로드 URL 발급", description = "파일의 SHA-256을 계산해 요청합니다. 반환된 URL·headers로 파일 바이너리를 PUT합니다. "
            + "MIME·최대 크기·유효시간은 서버 설정을 따릅니다. Content-Length는 byteSize와 같아야 하며 브라우저가 자동 설정합니다. "
            + "CSRF·인증 쿠키는 S3에 보내지 않습니다. "
            + "objectKey는 업로드 후 분석 요청에 전달하며, 영수증 키를 재고 실물 이미지로 저장하지 않습니다. "
            + "expiresAt은 서명 만료 시각이며 AWS 임시 자격증명이 먼저 만료되면 URL도 먼저 만료됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "발급 성공 (IMAGE-200-001)", content = @Content(
                    mediaType = "application/json", examples = @ExampleObject(value = ImageApiExamples.RESPONSE))),
            @ApiResponse(responseCode = "400", description = "입력 형식·MIME·크기 제한 오류 (GLOBAL-400-001)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    mediaType = "application/json", examples = @ExampleObject(value = "{\"code\":\"GLOBAL-400-001\",\"message\":\"요청 형식이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "401", description = "로그인 필요 (GLOBAL-401-001), 인증 토큰 만료·오류 (ACCESS_TOKEN_EXPIRED, ACCESS_TOKEN_INVALID)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class), mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"code\":\"GLOBAL-401-001\",\"message\":\"로그인이 필요합니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "CSRF 검증 실패 (COMMON-403-CSRF-001)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    mediaType = "application/json", examples = @ExampleObject(value = "{\"code\":\"COMMON-403-CSRF-001\",\"message\":\"CSRF 토큰이 없거나 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "500", description = "서명 또는 서버 오류 (GLOBAL-500-001)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    mediaType = "application/json", examples = @ExampleObject(value = "{\"code\":\"GLOBAL-500-001\",\"message\":\"서버에서 요청을 처리하지 못했습니다.\"}")))
    })
    ResponseEntity<SuccessResponse<ImagePresignResponse>> issue(@Parameter(hidden = true) Long userId,
            @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ImagePresignRequest.class),
                    examples = @ExampleObject(value = ImageApiExamples.REQUEST))) ImagePresignRequest request);
}
