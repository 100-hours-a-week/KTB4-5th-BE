package com.dameokja.backend.image.application;

import com.dameokja.backend.image.infrastructure.KakaoProfileImageDownloader;
import com.dameokja.backend.image.infrastructure.KakaoProfileImageDownloader.DownloadedImage;
import com.dameokja.backend.image.infrastructure.S3ProfileImageStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageService {
    private final KakaoProfileImageDownloader downloader;
    private final S3ProfileImageStorage storage;

    /**
     * 카카오 사진을 복사하고, 사진 처리 실패 시 기본 이미지 선택을 위해 빈 키를 반환합니다.
     * @param sourceUrl 카카오 사용자 정보에서 받은 사진 URL
     * @return 가입 트랜잭션에 연결하거나 닫아서 정리해야 하는 업로드 결과
     */
    public ImportedProfileImage importKakao(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return ImportedProfileImage.absent();
        }
        try {
            DownloadedImage image = downloader.download(sourceUrl);
            String key = storage.store(image);
            return ImportedProfileImage.uploaded(key, () -> storage.discard(key));
        } catch (RuntimeException exception) {
            log.warn("profile_image_import_failed cause={}", exception.getClass().getSimpleName());
            return ImportedProfileImage.absent();
        }
    }

    public String readUrl(String key) { return storage.readUrl(key); }

}
