package com.dameokja.backend.image.presentation.response;

import com.dameokja.backend.image.application.ImageUploadResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Map;

public record ImagePresignResponse(
        @Schema(description = "업로드 완료 후 분석 요청 등에 전달할 저장소 키") String objectKey,
        String uploadUrl, String method, Map<String, String> headers, OffsetDateTime expiresAt) {
    public static ImagePresignResponse from(ImageUploadResult result) {
        return new ImagePresignResponse(result.objectKey(), result.uploadUrl(), result.method(), result.headers(), result.expiresAt());
    }
}
