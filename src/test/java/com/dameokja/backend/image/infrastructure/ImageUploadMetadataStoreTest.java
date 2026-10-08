package com.dameokja.backend.image.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.image.domain.ImageUploadMetadata;
import com.dameokja.backend.image.domain.ImageUploadPurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ImageUploadMetadataStoreTest {
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-08T01:00:00Z");
    private final Duration ttl = Duration.ofMinutes(1);

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
        return new ImageUploadMetadataStore(clock, ttl);
    }
}
