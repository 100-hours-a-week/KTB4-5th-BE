package com.dameokja.backend.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.auth.domain.OAuthIdentity;
import com.dameokja.backend.auth.domain.OAuthProvider;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.image.infrastructure.KakaoProfileImageDownloader;
import com.dameokja.backend.support.ServiceIntegrationTest;
import com.dameokja.backend.user.application.OAuthSignupService;
import com.dameokja.backend.user.application.SignupResult;
import com.dameokja.backend.user.infrastructure.OAuthRegistrationStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

class OAuthProfileSignupIntegrationTest extends ServiceIntegrationTest {
    private static final String SOURCE = "https://img1.kakaocdn.net/profile/test.jpg";
    @Autowired OAuthSignupService signup;
    @Autowired OAuthRegistrationStore registrations;
    @Autowired JwtProvider jwt;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean KakaoProfileImageDownloader downloader;
    @MockitoBean S3Client client;

    @Test
    void copiesPhotoBeforeStartingTransactionAndPersistsOnlyItsKey() {
        when(downloader.download(SOURCE)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return image();
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        SignupResult result = signup.signup(token(SOURCE), "photo1", true);
        assertThat(savedKey(result)).isEqualTo(uploadedKey()).startsWith("test-images/profile/");
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(result.activeRefrigeratorIds()).hasSize(1);
    }

    @Test
    void absentPhotoCompletesSignupWithExistingDefault() {
        SignupResult result = signup.signup(token(null), "photo1", false);
        assertThat(savedKey(result)).isEqualTo("profiles/default.png");
        verifyNoInteractions(downloader, client);
    }

    @Test
    void downloadFailureCompletesSignupWithExistingDefault() {
        when(downloader.download(SOURCE)).thenThrow(new IllegalStateException("download failed"));
        SignupResult result = signup.signup(token(SOURCE), "photo1", false);
        assertThat(savedKey(result)).isEqualTo("profiles/default.png");
        verifyNoInteractions(client);
    }

    @Test
    void storageAndCleanupFailureStillCompleteSignupWithExistingDefault() {
        when(downloader.download(SOURCE)).thenReturn(image());
        doThrow(new IllegalStateException("upload failed")).when(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        doThrow(new IllegalStateException("cleanup failed")).when(client).deleteObject(any(DeleteObjectRequest.class));
        SignupResult result = signup.signup(token(SOURCE), "photo1", false);
        assertThat(savedKey(result)).isEqualTo("profiles/default.png");
        verifyDeletedKey(uploadedKey());
    }

    @Test
    void tokenFailureRollsBackDatabaseDeletesUploadedPhotoAndAllowsRetry() {
        when(downloader.download(SOURCE)).thenReturn(image());
        String token = token(SOURCE);
        doThrow(new IllegalStateException("token failed")).when(jwt).createRefreshToken(anyLong(), any(UUID.class));
        assertThatThrownBy(() -> signup.signup(token, "photo1", true)).hasMessage("token failed");
        assertEmptyDatabase();
        String key = uploadedKey();
        verifyDeletedKey(key);
        doCallRealMethod().when(jwt).createRefreshToken(anyLong(), any(UUID.class));
        assertThat(savedKey(signup.signup(token, "photo1", true))).isNotEqualTo(key);
    }

    @Test
    void photoIsDeletedOnlyAfterOuterTransactionRollsBack() {
        when(downloader.download(SOURCE)).thenReturn(image());
        String token = token(SOURCE);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            signup.signup(token, "photo1", true);
            verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
            status.setRollbackOnly();
        });
        assertEmptyDatabase();
        verifyDeletedKey(uploadedKey());
        assertThat(signup.signup(token, "photo1", true).tokenPair()).isNotNull();
    }

    private String token(String source) {
        return registrations.create(new OAuthIdentity(OAuthProvider.KAKAO, "123", "member@example.com", source));
    }

    private String savedKey(SignupResult result) {
        return userRepository.findById(result.tokenPair().userId()).orElseThrow().getProfileImageKey();
    }

    private String uploadedKey() {
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        return request.getValue().key();
    }

    private void verifyDeletedKey(String key) {
        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo(key);
    }

    private KakaoProfileImageDownloader.DownloadedImage image() {
        return new KakaoProfileImageDownloader.DownloadedImage(new byte[]{1}, "image/png");
    }
}
