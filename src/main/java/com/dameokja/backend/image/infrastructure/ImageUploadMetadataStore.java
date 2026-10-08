package com.dameokja.backend.image.infrastructure;

import com.dameokja.backend.image.domain.ImageUploadMetadata;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

@Repository
public class ImageUploadMetadataStore {
    private final Clock clock;
    private final Duration ttl;
    private final Cache<String, ImageUploadMetadata> uploads;

    public ImageUploadMetadataStore(Clock clock, @Value("${analysis.job-ttl}") Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("업로드 발급 내역 TTL은 양수여야 합니다.");
        }
        this.clock = clock;
        this.ttl = ttl;
        uploads = Caffeine.newBuilder().expireAfterWrite(ttl).build();
    }

    public void save(Long userId, ImageUploadPurpose purpose, String objectKey, String sha256) {
        uploads.put(objectKey, new ImageUploadMetadata(userId, purpose, sha256, clock.instant().plus(ttl)));
    }

    public Optional<ImageUploadMetadata> find(String objectKey) {
        ImageUploadMetadata upload = objectKey == null ? null : uploads.getIfPresent(objectKey);
        if (upload != null && !clock.instant().isBefore(upload.expiresAt())) {
            uploads.asMap().remove(objectKey, upload);
            return Optional.empty();
        }
        return Optional.ofNullable(upload);
    }
}
