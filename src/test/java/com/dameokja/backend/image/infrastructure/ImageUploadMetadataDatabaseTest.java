package com.dameokja.backend.image.infrastructure;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.image.domain.ImageUploadMetadata;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class ImageUploadMetadataDatabaseTest extends MySqlJpaTest {
    private final Instant now = Instant.parse("2026-11-05T00:00:00.123456789Z");
    private final Duration ttl = Duration.ofMinutes(1);
    private final Clock clock = mock(Clock.class);
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void switchesWritesAndReadsBothStoresWithExactKeysAndExpiration() {
        when(clock.instant()).thenReturn(now);
        ImageUploadMetadataRepository repository = new ImageUploadMetadataRepository(jdbcTemplate);
        ImageUploadMetadataStore legacy = store(repository, false);
        ImageUploadMetadataStore database = store(repository, true);
        legacy.save(7L, ImageUploadPurpose.ANALYSIS, "legacy", "ab".repeat(32));
        database.save(8L, ImageUploadPurpose.PROFILE, "Key ", "cd".repeat(32));
        assertThat(database.find("legacy")).get().extracting(ImageUploadMetadata::userId).isEqualTo(7L);
        assertThat(legacy.find("Key ")).contains(new ImageUploadMetadata(8L, ImageUploadPurpose.PROFILE,
                "cd".repeat(32), now.plus(ttl).plusNanos(211)));
        assertThat(database.find("key ")).isEmpty();
        assertThat(database.find("Key")).isEmpty();
        assertThat(redisTemplate().opsForValue().get("image:upload:Key ")).isNull();
        when(clock.instant()).thenReturn(now.plus(ttl));
        assertThat(database.find("Key ")).isPresent();
        when(clock.instant()).thenReturn(now.plus(ttl).plusNanos(211));
        assertThat(database.find("Key ")).isEmpty();
    }

    @Test
    void expiredDatabaseRowDoesNotReviveRedisAndCleanupIsBounded() {
        when(clock.instant()).thenReturn(now);
        ImageUploadMetadataRepository repository = new ImageUploadMetadataRepository(jdbcTemplate);
        ImageUploadMetadataStore legacy = store(repository, false);
        ImageUploadMetadataStore database = store(repository, true);
        legacy.save(7L, ImageUploadPurpose.ANALYSIS, "duplicate", "ab".repeat(32));
        database.save(8L, ImageUploadPurpose.PROFILE, "duplicate", "cd".repeat(32));
        database.save(8L, ImageUploadPurpose.PROFILE, "another", "cd".repeat(32));
        when(clock.instant()).thenReturn(now.plus(ttl).plusNanos(210));
        assertThat(repository.deleteExpired(clock.instant(), 1)).isZero();
        when(clock.instant()).thenReturn(now.plus(ttl).plusNanos(211));
        assertThat(database.find("duplicate")).isEmpty();
        assertThat(redisTemplate().hasKey("image:upload:duplicate")).isTrue();
        assertThat(repository.deleteExpired(clock.instant(), 1)).isEqualTo(1);
        assertThat(repository.deleteExpired(clock.instant(), 1)).isEqualTo(1);
        assertThat(repository.deleteExpired(clock.instant(), 1)).isZero();
    }

    private ImageUploadMetadataStore store(ImageUploadMetadataRepository repository, boolean databaseWriteEnabled) {
        return new ImageUploadMetadataStore(clock, ttl, redisTemplate(), JsonMapper.builder().build(), repository, databaseWriteEnabled);
    }
}
