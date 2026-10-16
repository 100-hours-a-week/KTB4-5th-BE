package com.dameokja.backend.image.infrastructure;

import com.dameokja.backend.image.infrastructure.KakaoProfileImageDownloader.DownloadedImage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@RequiredArgsConstructor
@Slf4j
public class S3ProfileImageStorage {
    private final ImageStorageProperties properties;
    private final S3Client client;
    private final S3Presigner presigner;

    /**
     * 새 키로 사진을 저장하고, 저장 실패 시 해당 키의 객체 정리를 시도합니다.
     * @param image 다운로드와 검증을 마친 사진
     * @return 저장된 S3 객체 키
     */
    public String store(DownloadedImage image) {
        String key = properties.prefix() + "profile/" + UUID.randomUUID();
        PutObjectRequest request = PutObjectRequest.builder().bucket(properties.bucket()).key(key)
                .contentType(image.contentType()).build();
        try {
            client.putObject(request, RequestBody.fromBytes(image.content()));
            return key;
        } catch (RuntimeException exception) {
            // 응답 시간 초과라면 실제 저장됐을 수 있으므로 시도한 키를 정리한다.
            discard(key);
            throw exception;
        }
    }

    /**
     * 사용하지 않을 사진을 삭제하며, 삭제 실패가 원래 가입 결과를 덮어쓰지 않도록 기록합니다.
     * @param key 정리할 S3 객체 키
     */
    public void discard(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
        } catch (RuntimeException exception) {
            log.warn("profile_image_cleanup_failed key={} cause={}", key, exception.getClass().getSimpleName());
        }
    }

    public String readUrl(String key) {
        GetObjectRequest object = GetObjectRequest.builder().bucket(properties.bucket()).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.presignedUrlTtl()).getObjectRequest(object).build()).url().toExternalForm();
    }
}
