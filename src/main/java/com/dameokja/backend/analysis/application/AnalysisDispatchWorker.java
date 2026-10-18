package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.infrastructure.AiAnalysisClient;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisDispatchWorker {
    private final AnalysisDispatchLeaseService analysisDispatchLeaseService;
    private final AnalysisDispatchSender analysisDispatchSender;
    private final AnalysisDispatchChecker analysisDispatchChecker;
    private final AiAnalysisClient aiAnalysisClient;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // 기한이 지난 작업을 먼저 정리해야 슬롯이 비어 새 작업을 보낼 수 있다.
    public synchronized void poll() {
        analysisDispatchLeaseService.leaseExpired().forEach(leasedDispatch -> start(() -> analysisDispatchChecker.check(leasedDispatch)));
        // 전송기는 기한이 지난 행을 AI에 보내지 않고 ANALYSIS_EXPIRED로 끝낸다.
        analysisDispatchLeaseService.leaseExpiredPending().forEach(leasedDispatch -> start(() -> analysisDispatchSender.send(leasedDispatch)));
        if (!aiAnalysisClient.ready()) {
            return;
        }
        analysisDispatchLeaseService.leaseDue().forEach(leasedDispatch -> start(() -> analysisDispatchSender.send(leasedDispatch)));
    }

    // 콜백·접수 직후 주기를 기다리지 않고 다음 작업을 보낸다. 실패해도 주기 실행이 이어서 처리한다.
    public void wakeUp() {
        start(this::poll);
    }

    private void start(Runnable task) {
        try {
            executor.execute(() -> run(task));
        } catch (RejectedExecutionException exception) {
            log.warn("분석 전달 작업을 시작하지 못했습니다. 선점 기한 뒤 다시 처리합니다.", exception);
        }
    }

    private void run(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            log.error("분석 전달 작업을 처리하지 못했습니다.", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
