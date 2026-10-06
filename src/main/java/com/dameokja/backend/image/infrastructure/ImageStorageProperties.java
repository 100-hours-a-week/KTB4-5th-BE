package com.dameokja.backend.image.infrastructure;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("image.storage")
public record ImageStorageProperties(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") String bucket,
        @NotBlank String region,
        @NotNull @Pattern(regexp = "[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*/") String prefix,
        @NotNull Duration presignedUrlTtl,
        @Positive long maxUploadBytes,
        @NotEmpty Set<@NotBlank @Pattern(regexp = "image/(jpeg|png|webp)") String> allowedContentTypes
) {
    private static final Duration MAX_PRESIGNED_URL_TTL = Duration.ofDays(7);

    @AssertTrue(message = "presigned URL 유효시간은 1초 이상 7일 이하여야 합니다.")
    public boolean isValidPresignedUrlTtl() {
        return presignedUrlTtl != null && presignedUrlTtl.compareTo(Duration.ofSeconds(1)) >= 0
                && presignedUrlTtl.compareTo(MAX_PRESIGNED_URL_TTL) <= 0;
    }
}
