package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationOutbox;
import com.dameokja.backend.recipe.infrastructure.RecommendationOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RecommendationOutboxQueue {
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final RecommendationOutboxRepository recommendationOutboxRepository;
    private final Clock clock;

    public record Lease(Long id, String token, String topic, String key, String body) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Lease> leaseBatch(int batchSize, Duration leaseDuration) {
        validateDuration(leaseDuration);
        if (batchSize < 1) {
            throw new IllegalArgumentException("발행 배치 크기는 양수여야 합니다.");
        }
        LocalDateTime currentTime = BusinessTime.now(clock);
        String sqlTime = currentTime.format(SQL_TIME);
        List<RecommendationOutbox> outboxBatch = new ArrayList<>(recommendationOutboxRepository.findExpiredForUpdate(sqlTime, batchSize));
        outboxBatch.addAll(recommendationOutboxRepository.findPendingForUpdate(sqlTime, batchSize - outboxBatch.size()));
        LocalDateTime leaseExpiration = currentTime.plus(leaseDuration);
        List<Lease> leases = new ArrayList<>();
        for (RecommendationOutbox outbox : outboxBatch) {
            outbox.lease(UUID.randomUUID().toString(), leaseExpiration);
            leases.add(new Lease(outbox.getId(), outbox.getLeaseToken(), outbox.getTopic(), outbox.getMessageKey(), outbox.getMessageBody()));
        }
        return List.copyOf(leases);
    }

    public int markPublished(Lease lease) {
        return recommendationOutboxRepository.markPublished(lease.id(), lease.token(), BusinessTime.now(clock).format(SQL_TIME));
    }

    public int retryLater(Lease lease, Duration retryDelay) {
        validateDuration(retryDelay);
        LocalDateTime currentTime = BusinessTime.now(clock);
        return recommendationOutboxRepository.retryLater(lease.id(), lease.token(), currentTime.format(SQL_TIME), currentTime.plus(retryDelay).format(SQL_TIME));
    }

    private void validateDuration(Duration duration) {
        if (duration == null || duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("발행 대기 시간은 1ms 이상이어야 합니다.");
        }
    }
}
