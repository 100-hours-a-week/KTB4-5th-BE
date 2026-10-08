package com.dameokja.backend.image.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.image.infrastructure.ImageStorageProperties;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.image.infrastructure.S3ImageUploadSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

class ImagePresignServiceTest {
    private final ImageStorageProperties properties = new ImageStorageProperties("test-bucket", "us-east-1",
            "test-images/", Duration.ofMinutes(5), 2048, Set.of("image/jpeg", "image/png", "image/webp"));
    private final ImageUploadMetadataStore uploads = new ImageUploadMetadataStore(Clock.systemUTC(), Duration.ofHours(1));

    @ParameterizedTest
    @CsvSource({"ANALYSIS,image/jpeg,jpg", "ANALYSIS,image/png,png", "PROFILE,image/webp,webp"})
    void signsUploadWithUserPurposeSizeAndChecksum(ImageUploadPurpose purpose, String contentType, String extension) {
        try (S3Presigner presigner = presigner()) {
            S3ImageUploadSigner signer = new S3ImageUploadSigner(properties, presigner);
            ImagePresignService service = new ImagePresignService(properties, signer, uploads);
            ImageUploadResult result = service.issue(7L, purpose, contentType, 2048L, "ab".repeat(32));
            assertThat(result.objectKey()).matches("test-images/" + purpose.name().toLowerCase(java.util.Locale.ROOT)
                    + "/7/[0-9a-f-]{36}\\." + extension);
            assertThat(result.method()).isEqualTo("PUT");
            assertThat(result.headers()).containsEntry("Content-Type", contentType)
                    .containsEntry("x-amz-checksum-sha256", "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=");
            assertThat(result.uploadUrl()).contains(result.objectKey(), "X-Amz-Expires=300", "X-Amz-Signature=");
            assertThat(result.expiresAt()).isBetween(OffsetDateTime.now().plusSeconds(295), OffsetDateTime.now().plusSeconds(305));
            assertThat(uploads.find(result.objectKey()).orElseThrow()).satisfies(upload -> {
                assertThat(upload.userId()).isEqualTo(7L);
                assertThat(upload.purpose()).isEqualTo(purpose);
                assertThat(upload.sha256()).isEqualTo("ab".repeat(32));
            });
            assertThat(service.issue(7L, purpose, contentType, 1L, "ab".repeat(32)).objectKey()).isNotEqualTo(result.objectKey());
            PresignedPutObjectRequest signed = signer.sign(result.objectKey(), contentType, 2048L, "ab".repeat(32));
            assertThat(signed.signedHeaders()).containsKeys("content-type", "content-length", "x-amz-checksum-sha256");
            assertThat(signed.signedHeaders().get("content-length")).containsExactly("2048");
        }
    }

    @ParameterizedTest
    @CsvSource({"image/gif,1", "image/png,2049"})
    void rejectsUnsupportedMimeOrOversizedImage(String contentType, long byteSize) {
        try (S3Presigner presigner = presigner()) {
            ImagePresignService service = new ImagePresignService(properties, new S3ImageUploadSigner(properties, presigner), uploads);
            assertThatThrownBy(() -> service.issue(7L, ImageUploadPurpose.ANALYSIS, contentType, byteSize, "ab".repeat(32)))
                    .isInstanceOf(CustomException.class).extracting("exceptionCode.code").isEqualTo("GLOBAL-400-001");
        }
    }

    private S3Presigner presigner() {
        return S3Presigner.builder().region(Region.US_EAST_1).credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access-key", "test-secret-key"))).build();
    }
}
