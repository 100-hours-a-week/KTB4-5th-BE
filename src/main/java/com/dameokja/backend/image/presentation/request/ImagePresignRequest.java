package com.dameokja.backend.image.presentation.request;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record ImagePresignRequest(
        @Schema(description = "ANALYSIS: 영수증 등 분석용 이미지, PROFILE: 프로필 사진") @NotNull ImageUploadPurpose purpose,
        @NotBlank String contentType,
        @Schema(type = "integer", format = "int64") @NotNull @Positive BigDecimal byteSize,
        @Schema(description = "파일 바이너리의 SHA-256, 소문자 16진수 64자리")
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256
) {
    public long byteSizeAsLong() {
        try {
            return byteSize.longValueExact();
        } catch (ArithmeticException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
    }
}
