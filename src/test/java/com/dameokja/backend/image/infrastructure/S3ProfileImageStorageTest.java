package com.dameokja.backend.image.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.dameokja.backend.global.exception.CustomException;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ProfileImageStorageTest {
    private final ImageStorageProperties properties = new ImageStorageProperties("test-bucket", "us-east-1", "images/",
            Duration.ofMinutes(5), 1024, Set.of("image/jpeg"));
    private final S3Client client = mock(S3Client.class);

    @Test
    void storesPrivateObjectAndReturnsExpiringGetUrlForSameKey() {
        try (S3Presigner presigner = S3Presigner.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret"))).build()) {
            S3ProfileImageStorage storage = new S3ProfileImageStorage(properties, client, presigner);
            String key = storage.store(image());
            ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(client).putObject(request.capture(), any(RequestBody.class));
            assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
            assertThat(request.getValue().key()).isEqualTo(key).matches("images/profile/[0-9a-f-]{36}");
            assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
            assertThat(request.getValue().acl()).isNull();
            assertThat(storage.readUrl(key))
                    .startsWith("https://test-bucket.s3.amazonaws.com/" + key + "?")
                    .contains("X-Amz-Expires=300", "X-Amz-Signature=");
        }
    }

    @Test
    void ambiguousUploadFailureCleansTheAttemptedObjectAndPreservesFailure() {
        S3ProfileImageStorage storage = new S3ProfileImageStorage(properties, client, mock(S3Presigner.class));
        IllegalStateException failure = new IllegalStateException("upload timeout");
        doThrow(failure).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThatThrownBy(() -> storage.store(image())).isSameAs(failure);
        ArgumentCaptor<PutObjectRequest> uploaded = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).putObject(uploaded.capture(), any(RequestBody.class));
        verify(client).deleteObject(deleted.capture());
        assertThat(deleted.getValue().bucket()).isEqualTo(uploaded.getValue().bucket());
        assertThat(deleted.getValue().key()).isEqualTo(uploaded.getValue().key());
    }

    @Test
    void cleanupFailureDoesNotReplaceOriginalUploadFailure() {
        S3ProfileImageStorage storage = new S3ProfileImageStorage(properties, client, mock(S3Presigner.class));
        IllegalStateException failure = new IllegalStateException("upload timeout");
        doThrow(failure).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        doThrow(new IllegalStateException("delete failed")).when(client).deleteObject(any(DeleteObjectRequest.class));
        assertThatThrownBy(() -> storage.store(image())).isSameAs(failure);
        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    private KakaoProfileImageDownloader.DownloadedImage image() {
        return new KakaoProfileImageDownloader.DownloadedImage(new byte[]{1, 2, 3}, "image/jpeg");
    }

    @Test
    void acceptsUploadedImageWithinConfiguredLimits() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(1024L).contentType("image/jpeg").build());
        storage().validateUploaded("uploaded-key");
        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(request.getValue().key()).isEqualTo("uploaded-key");
    }

    @Test
    void refusesMissingUpload() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertThatThrownBy(() -> storage().validateUploaded("uploaded-key")).isInstanceOf(CustomException.class);
    }

    private S3ProfileImageStorage storage() {
        return new S3ProfileImageStorage(properties, client, mock(S3Presigner.class));
    }

}
