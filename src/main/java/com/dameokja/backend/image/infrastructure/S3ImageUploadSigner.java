package com.dameokja.backend.image.infrastructure;

import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class S3ImageUploadSigner {
    private final ImageStorageProperties properties;
    private final S3Presigner presigner;

    public PresignedPutObjectRequest sign(String objectKey, String contentType, long byteSize, String sha256) {
        String checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256));
        PutObjectRequest upload = PutObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                .contentType(contentType).contentLength(byteSize).checksumSHA256(checksum).build();
        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(properties.presignedUrlTtl()).putObjectRequest(upload).build();
        return presigner.presignPutObject(request);
    }
}
