package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.recipe.domain.RecommendationDailyJob;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
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
public class RecommendationDailyJobQueue {
    private static final DateTimeFormatter SQL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final RecommendationDailyJobRepository repository;
    private final Clock clock;

    public record Lease(Long jobId, LocalDate businessDate, Long userId, Long refrigeratorId, String requestId, String leaseToken) {}

    public void register(Long userId, Long refrigeratorId, LocalDate businessDate) {
        repository.register(businessDate, userId, refrigeratorId, UUID.randomUUID().toString(), BusinessTime.now(clock).format(SQL_DATE_TIME_FORMATTER));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Lease> leaseBatch(LocalDate businessDate, int batchSize, Duration leaseDuration) {
        if (batchSize < 1 || leaseDuration.toMillis() < 1) { throw new IllegalArgumentException("배치 크기와 선점 기간은 양수여야 합니다."); }
        LocalDateTime now = BusinessTime.now(clock);
        List<Lease> leases = new ArrayList<>();
        for (RecommendationDailyJob job : repository.findReadyForUpdate(businessDate, now.format(SQL_DATE_TIME_FORMATTER), batchSize)) {
            String token = UUID.randomUUID().toString();
            job.claim(token, now.plus(leaseDuration));
            leases.add(new Lease(job.getId(), job.getBusinessDate(), job.getUser().getId(), job.getRefrigerator().getId(), job.getRequestId(), token));
        }
        return List.copyOf(leases);
    }

    public boolean skip(Lease lease, String reason) {
        RecommendationDailyJob job = repository.findClaimedForUpdate(lease.jobId(), lease.leaseToken(), BusinessTime.now(clock).format(SQL_DATE_TIME_FORMATTER)).orElse(null);
        if (job == null) { return false; }
        job.skip(reason);
        return true;
    }
}
