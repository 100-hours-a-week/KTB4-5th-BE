package com.dameokja.backend.global.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class OutboxPublicationBatch {
    private OutboxPublicationBatch() {}

    // 선점한 배치만 함께 실행하고 Kafka I/O 스레드에서는 DB 완료 기록을 수행하지 않는다.
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

    public static <T> void deliver(T message, Function<T, ? extends Future<?>> publication, Consumer<T> completion, BiConsumer<T, Exception> failure) {
        try {
            Future<?> publicationResult = publication.apply(message);
            publicationResult.get();
            completion.accept(message);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException exception) {
            failure.accept(message, exception);
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
            // 한 행의 재예약 실패가 다른 행의 ACK·완료 기록을 막지 않게 한다.
            log.error("Outbox 개별 발행 처리를 완료하지 못했습니다. 선점 만료 후 복구합니다.", exception.getCause());
            return true;
        }
    }
}
