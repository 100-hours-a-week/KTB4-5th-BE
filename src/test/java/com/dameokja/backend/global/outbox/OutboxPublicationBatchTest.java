package com.dameokja.backend.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OutboxPublicationBatchTest {
    @Test
    void doesNotStartAnInterruptedBatch() {
        AtomicBoolean published = new AtomicBoolean();
        Thread.currentThread().interrupt();
        try {
            OutboxPublicationBatch.publish(List.of(1), message -> published.set(true));
            assertThat(published).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptsPendingAckWithoutCompletingOrRetryingItsLease() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        CompletableFuture<Void> ack = new CompletableFuture<>();
        CompletableFuture<Boolean> finished = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean retried = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            OutboxPublicationBatch.publish(List.of(1), message -> OutboxPublicationBatch.deliver(message, value -> {
                started.complete(null);
                return ack;
            }, value -> completed.set(true), (value, exception) -> retried.set(true)));
            finished.complete(Thread.currentThread().isInterrupted());
        });
        try {
            started.get(5, TimeUnit.SECONDS);
            worker.interrupt();
            assertThat(finished.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(completed).isFalse();
            assertThat(retried).isFalse();
        } finally {
            ack.complete(null);
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertThat(worker.isAlive()).isFalse();
    }

    @Test
    void propagatesFatalErrorsFromPublicationTasks() {
        assertThatThrownBy(() -> OutboxPublicationBatch.publish(List.of(1), message -> {
            throw new AssertionError("fatal publication failure");
        })).isInstanceOf(AssertionError.class).hasMessage("fatal publication failure");
    }
}
