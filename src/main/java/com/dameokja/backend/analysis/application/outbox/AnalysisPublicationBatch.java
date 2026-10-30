package com.dameokja.backend.analysis.application.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AnalysisPublicationBatch {
    private AnalysisPublicationBatch() {}

    // 선점한 배치만 동시에 실행하고, Kafka I/O 스레드에서는 DB 완료 기록을 수행하지 않는다.
    public static <T> void publish(List<T> batch, Consumer<T> publication) {
        if (Thread.currentThread().isInterrupted()) { return; }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> pending = new ArrayList<>();
            for (T message : batch) {
                pending.add(executor.submit(() -> publication.accept(message)));
            }
            for (Future<?> delivery : pending) {
                if (!await(delivery, executor)) { return; }
            }
        }
    }

    private static boolean await(Future<?> delivery, ExecutorService executor) {
        try {
            delivery.get();
            return true;
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Error error) { throw error; }
            // 한 행의 DB 재예약까지 실패해도 다른 행의 ACK·완료 기록은 계속 진행한다.
            log.error("분석 이벤트의 개별 발행 처리를 완료하지 못했습니다. 선점 만료 후 복구합니다.", exception.getCause());
            return true;
        }
    }
}
