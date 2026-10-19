package com.dameokja.backend.image.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.exception.ImageAnalysisExceptionCode;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImageAnalysisInputService {
    private final ImageUploadMetadataStore imageUploadMetadataStore;

    public String findAnalysisSha256(Long userId, String objectKey) {
        return imageUploadMetadataStore.find(objectKey)
                .filter(imageUploadMetadata -> userId != null && userId.equals(imageUploadMetadata.userId())
                        && imageUploadMetadata.purpose() == ImageUploadPurpose.ANALYSIS)
                .map(imageUploadMetadata -> imageUploadMetadata.sha256())
                .orElseThrow(() -> new CustomException(ImageAnalysisExceptionCode.INVALID_IMAGE));
    }
}
