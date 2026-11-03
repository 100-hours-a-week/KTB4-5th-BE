package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.domain.RecommendationOutbox.Status;
import com.dameokja.backend.recipe.infrastructure.RecommendationJobStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationWorker {
    private final RecommendationOutboxService recommendationOutboxService;
    private final RecommendationExecutionService recommendationExecutionService;
    private final RecommendationJobStore recommendationJobStore;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> activeEventIds = ConcurrentHashMap.newKeySet();
    @Value("${recipe.dispatch.enabled:true}") private boolean dispatchEnabled;
    // AI에 동시에 맡기는 추천 수. 한 번의 폴링에서 이 수에서 처리 중인 건을 뺀 만큼만 outbox를 읽는다.
    @Value("${recipe.dispatch.concurrency:4}") private int maxConcurrentRecommendations;
    private long lastScannedOutboxId;

    @PostConstruct
    void validateConcurrency() {
        if (maxConcurrentRecommendations < 1) {
            throw new IllegalArgumentException("추천 동시 전달 수는 1 이상이어야 합니다.");
        }
    }

    @Scheduled(fixedDelayString = "${recipe.dispatch.poll-interval:5s}")
    public void scheduledPoll() {
        if (dispatchEnabled) { poll(); }
    }

    public synchronized void poll() {
        if (executorService.isShutdown() || activeEventIds.size() >= maxConcurrentRecommendations) { return; }
        List<RecommendationOutboxService.Event> outboxEvents = recommendationOutboxService.batch(lastScannedOutboxId, maxConcurrentRecommendations - activeEventIds.size());
        if (outboxEvents.isEmpty()) {
            lastScannedOutboxId = 0;
            return;
        }
        for (RecommendationOutboxService.Event outboxEvent : outboxEvents) {
            lastScannedOutboxId = outboxEvent.sequence();
            if (outboxEvent.status() != Status.FAILED && activeEventIds.add(outboxEvent.eventId())) {
                try {
                    executorService.execute(() -> process(outboxEvent));
                } catch (RejectedExecutionException exception) {
                    activeEventIds.remove(outboxEvent.eventId());
                    throw exception;
                }
            }
        }
    }

    private void process(RecommendationOutboxService.Event outboxEvent) {
        try {
            if (outboxEvent.status() == Status.PENDING) {
                recommendationExecutionService.submit(outboxEvent.eventId());
            } else if (recommendationJobStore.find(outboxEvent.eventId()).isEmpty()) {
                return;
            }
            recommendationExecutionService.refresh(outboxEvent.eventId());
        } catch (RuntimeException exception) {
            log.warn("추천 전달·조회 실패 eventId={}", outboxEvent.eventId(), exception);
        } finally {
            activeEventIds.remove(outboxEvent.eventId());
        }
    }

    boolean isIdle() { return activeEventIds.isEmpty(); }

    @PreDestroy
    public void shutdown() { executorService.shutdownNow(); }
}
