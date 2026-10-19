package com.dameokja.backend.image.infrastructure;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.image.domain.ImageUploadMetadata;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class ImageUploadMetadataStoreTest {
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-08T01:00:00Z");
    private final Duration ttl = Duration.ofMinutes(1);

    @Test
    void anotherInstanceReadsSharedMetadataAndRedisKeepsItsTtlOnRead() {
        ImageUploadMetadataStore first = store();
        first.save(7L, ImageUploadPurpose.ANALYSIS, "shared-key", "ab".repeat(32));
        Long before = redisTemplate().getExpire("image:upload:shared-key", TimeUnit.MILLISECONDS);
        ImageUploadMetadataStore second = new ImageUploadMetadataStore(clock, ttl, redisTemplate(), JsonMapper.builder().build());
        assertThat(second.find("shared-key")).contains(new ImageUploadMetadata(7L, ImageUploadPurpose.ANALYSIS, "ab".repeat(32), now.plus(ttl)));
        assertThat(before).isBetween(1L, ttl.toMillis());
        assertThat(redisTemplate().getExpire("image:upload:shared-key", TimeUnit.MILLISECONDS)).isBetween(1L, before);
    }

    @Test
    void retainsSeparateChecksumsForObjectKeysWithTheSamePurpose() {
        ImageUploadMetadataStore store = store();
        store.save(7L, ImageUploadPurpose.ANALYSIS, "first-key", "ab".repeat(32));
        store.save(7L, ImageUploadPurpose.ANALYSIS, "second-key", "cd".repeat(32));
        assertThat(store.find("first-key")).contains(new ImageUploadMetadata(
                7L, ImageUploadPurpose.ANALYSIS, "ab".repeat(32), now.plus(ttl)));
        assertThat(store.find("second-key")).contains(new ImageUploadMetadata(
                7L, ImageUploadPurpose.ANALYSIS, "cd".repeat(32), now.plus(ttl)));
    }

    @Test
    void expiresAtTheBoundaryWithoutExtendingLifetimeOnRead() {
        ImageUploadMetadataStore store = store();
        store.save(7L, ImageUploadPurpose.ANALYSIS, "issued-key", "ab".repeat(32));
        when(clock.instant()).thenReturn(now.plus(ttl).minusNanos(1));
        assertThat(store.find("issued-key")).isPresent();
        when(clock.instant()).thenReturn(now.plus(ttl));
        assertThat(store.find("issued-key")).isEmpty();
        when(clock.instant()).thenReturn(now);
        assertThat(store.find("issued-key")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "unissued-key")
    void returnsEmptyForObjectKeysWithoutAnIssuanceRecord(String key) {
        assertThat(store().find(key)).isEmpty();
    }

    private ImageUploadMetadataStore store() {
        when(clock.instant()).thenReturn(now);
        return new ImageUploadMetadataStore(clock, ttl, redisTemplate(), JsonMapper.builder().build());
    }
}
