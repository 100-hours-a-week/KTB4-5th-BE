package com.dameokja.backend.image.infrastructure;

import com.dameokja.backend.image.domain.ImageUploadMetadata;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ImageUploadMetadataStore {
    private static final String PREFIX = "image:upload:";
    private static final RedisScript<Long> REMOVE_EXPIRED = RedisScript.of(
            new ClassPathResource("redis/image/remove-expired-upload.lua"), Long.class);
    private final Clock clock;
    private final Duration ttl;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ImageUploadMetadataStore(Clock clock, @Value("${analysis.job-ttl}") Duration ttl,
            StringRedisTemplate redis, ObjectMapper mapper) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("업로드 발급 내역 TTL은 양수여야 합니다.");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.redis = redis;
        this.mapper = mapper;
    }

    public void save(Long userId, ImageUploadPurpose purpose, String objectKey, String sha256) {
        ImageUploadMetadata upload = new ImageUploadMetadata(userId, purpose, sha256, clock.instant().plus(ttl));
        redis.opsForValue().set(PREFIX + objectKey, mapper.writeValueAsString(upload), Duration.ofMillis(Math.max(1, ttl.toMillis())));
    }

    public Optional<ImageUploadMetadata> find(String objectKey) {
        String stored = objectKey == null ? null : redis.opsForValue().get(PREFIX + objectKey);
        if (stored == null) {
            return Optional.empty();
        }
        ImageUploadMetadata upload = mapper.readValue(stored, ImageUploadMetadata.class);
        if (!clock.instant().isBefore(upload.expiresAt())) {
            // 만료 확인 이후 새 발급 내역이 저장되어도 이전 값만 제거한다.
            redis.execute(REMOVE_EXPIRED, List.of(PREFIX + objectKey), stored);
            return Optional.empty();
        }
        return Optional.of(upload);
    }
}
