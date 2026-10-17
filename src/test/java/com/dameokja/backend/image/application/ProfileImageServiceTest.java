package com.dameokja.backend.image.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.image.infrastructure.KakaoProfileImageDownloader;
import com.dameokja.backend.image.infrastructure.S3ProfileImageStorage;
import com.dameokja.backend.image.infrastructure.ImageUploadMetadataStore;
import com.dameokja.backend.image.domain.ImageUploadMetadata;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.global.exception.CustomException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileImageServiceTest {
    private final KakaoProfileImageDownloader downloader = mock(KakaoProfileImageDownloader.class);
    private final S3ProfileImageStorage storage = mock(S3ProfileImageStorage.class);
    private final ImageUploadMetadataStore uploads = mock(ImageUploadMetadataStore.class);
    private final ProfileImageService service = new ProfileImageService(downloader, storage, uploads);

    @Test
    void validatesOwnersUploadedProfile() {
        when(uploads.find("key")).thenReturn(Optional.of(new ImageUploadMetadata(1L, ImageUploadPurpose.PROFILE, "checksum", Instant.MAX)));
        service.validateUpload(1L, "key");
        verify(storage).validateUploaded("key");
    }

    @Test
    void rejectsAnotherUsersPhotoBeforeAccessingS3() {
        when(uploads.find("key")).thenReturn(Optional.of(new ImageUploadMetadata(2L, ImageUploadPurpose.PROFILE, "checksum", Instant.MAX)));
        assertThatThrownBy(() -> service.validateUpload(1L, "key")).isInstanceOf(CustomException.class);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void absentPhotoKeepsExistingDefaultWithoutCallingExternalServices(String source) {
        try (ImportedProfileImage image = service.importKakao(source)) {
            assertThat(image.key()).isNull();
        }
        verifyNoInteractions(downloader, storage);
    }

    @Test
    void downloadFailureUsesDefaultWithoutUploading() {
        when(downloader.download("source")).thenThrow(new IllegalStateException("download failed"));
        try (ImportedProfileImage image = service.importKakao("source")) {
            assertThat(image.key()).isNull();
        }
        verifyNoInteractions(storage);
    }

    @Test
    void storageFailureUsesDefault() {
        when(downloader.download("source")).thenReturn(new KakaoProfileImageDownloader.DownloadedImage(new byte[]{1}, "image/jpeg"));
        doThrow(new IllegalStateException("S3 timeout")).when(storage).store(any());
        try (ImportedProfileImage image = service.importKakao("source")) {
            assertThat(image.key()).isNull();
        }
    }

    @Test
    void unclaimedImageIsRemovedWhenRegistrationCannotStart() {
        when(downloader.download("source")).thenReturn(new KakaoProfileImageDownloader.DownloadedImage(new byte[]{1}, "image/jpeg"));
        when(storage.store(any())).thenReturn("stored-profile-key");
        String uploadedKey;
        try (ImportedProfileImage image = service.importKakao("source")) {
            uploadedKey = image.key();
            assertThat(uploadedKey).isEqualTo("stored-profile-key");
        }
        verify(storage).discard(uploadedKey);
    }
}
