package com.dameokja.backend.image.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageStorageProperties;
import com.dameokja.backend.image.infrastructure.S3ImageUploadSigner;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

@Service
@RequiredArgsConstructor
public class ImagePresignService {
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private final ImageStorageProperties properties;
    private final S3ImageUploadSigner signer;

    public ImageUploadResult issue(Long userId, ImageUploadPurpose purpose, String contentType, long byteSize, String sha256) {
        validateUpload(contentType, byteSize);
        String objectKey = properties.prefix() + purpose.name().toLowerCase(Locale.ROOT) + "/" + userId
                + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
        PresignedPutObjectRequest signed = signer.sign(objectKey, contentType, byteSize, sha256);
        Map<String, String> headers = Map.of("Content-Type", contentType, "x-amz-checksum-sha256",
                signed.httpRequest().firstMatchingHeader("x-amz-checksum-sha256").orElseThrow());
        return new ImageUploadResult(objectKey, signed.url().toExternalForm(), "PUT", headers,
                signed.expiration().atZone(BusinessTime.ZONE).toOffsetDateTime());
    }

    private void validateUpload(String contentType, long byteSize) {
        if (!properties.allowedContentTypes().contains(contentType) || byteSize <= 0 || byteSize > properties.maxUploadBytes()) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
    }
}
