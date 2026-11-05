package com.dameokja.backend.image.infrastructure;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "image.upload-metadata.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ImageUploadMetadataCleanupScheduler {
    private final ImageUploadMetadataRepository repository;
    private final Clock clock;
    private final int batchSize;

    public ImageUploadMetadataCleanupScheduler(ImageUploadMetadataRepository repository, Clock clock,
            @Value("${image.upload-metadata.cleanup.batch-size:100}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("image.upload-metadata.cleanup.batch-size must be positive");
        }
        this.repository = repository;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${image.upload-metadata.cleanup.poll-interval:1m}")
    public void poll() {
        repository.deleteExpired(clock.instant(), batchSize);
    }
}
