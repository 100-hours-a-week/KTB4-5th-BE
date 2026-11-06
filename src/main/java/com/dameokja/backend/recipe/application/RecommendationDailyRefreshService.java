package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.application.RecommendationDailyJobQueue.Lease;
import com.dameokja.backend.recipe.domain.RecommendationDailyJob;
import com.dameokja.backend.recipe.infrastructure.RecommendationDailyJobRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService.RecommendationTarget;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class RecommendationDailyRefreshService {
    private final RefrigeratorAccessService refrigerators;
    private final RecommendationDailyJobQueue queue;
    private final RecommendationScheduledSubmissionService submissions;
    private final RecommendationDailyJobRepository jobs;
    @Value("${recommendation.daily-refresh.batch-size:20}") private int batchSize;
    @Value("${recommendation.daily-refresh.lease-duration:2m}") private Duration leaseDuration;

    public void registerTargets(LocalDate date) {
        if (batchSize < 1) { throw new IllegalArgumentException("배치 크기는 양수여야 합니다."); }
        long after = 0;
        int registered = 0;
        List<RecommendationTarget> targets;
        do {
            targets = refrigerators.findRecommendationTargetsAfter(after, batchSize);
            for (RecommendationTarget target : targets) {
                queue.register(target.userId(), target.refrigeratorId(), date);
                after = target.membershipId();
                registered++;
            }
        } while (targets.size() == batchSize);
        log.info("일일 추천 대상 등록 date={} targets={}", date, registered);
    }

    public void processBatch(LocalDate date) {
        long started = System.nanoTime();
        List<Lease> leases = queue.leaseBatch(date, batchSize, leaseDuration);
        if (leases.isEmpty()) { return; }
        int failed = 0;
        for (Lease lease : leases) {
            try { submissions.submit(lease); }
            catch (RuntimeException exception) {
                failed++;
                log.error("일일 추천 접수 실패 jobId={} requestId={}", lease.jobId(), lease.requestId(), exception);
            }
        }
        Map<RecommendationDailyJob.Status, Long> counts = jobs.findAllById(leases.stream().map(Lease::jobId).toList()).stream()
                .collect(Collectors.groupingBy(RecommendationDailyJob::getStatus, Collectors.counting()));
        log.info("일일 추천 처리 date={} processed={} failed={} statuses={} elapsedMs={}", date, leases.size(), failed, counts, (System.nanoTime() - started) / 1_000_000);
    }
}
