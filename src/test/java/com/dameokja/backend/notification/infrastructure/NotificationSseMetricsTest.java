package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SseEmitter first = mock(SseEmitter.class);
    private final SseEmitter second = mock(SseEmitter.class);
    private final SseEmitter otherUser = mock(SseEmitter.class);
    private NotificationSseConnections connections;
    private Gauge gauge;

    @BeforeEach
    void setup() {
        ArrayDeque<SseEmitter> emitters = new ArrayDeque<>(List.of(first, second, otherUser));
        connections = new NotificationSseConnections(emitters::remove, mock(NotificationConnectionPresence.class));
        new NotificationSseMetrics(connections).bindTo(registry);
        gauge = registry.get("sse.connections.active").gauge();
    }

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void countsEveryLocalConnectionIncludingMultipleStreamsPerUser() {
        assertThat(gauge.value()).isZero();
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        connections.connect(2L, 17L);

        assertThat(gauge.value()).isEqualTo(3);
        assertThat(gauge.getId().getTags()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"completion", "timeout", "error"})
    void closingConnectionIsCountedOnceAndReconnectIsCountedSeparately(String reason) {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        Runnable close = closeCallback(first, reason);

        close.run();
        close.run();
        assertThat(gauge.value()).isEqualTo(1);
        connections.connect(1L, 17L);
        assertThat(gauge.value()).isEqualTo(2);
    }

    @Test
    void initialSendFailureLeavesNoActiveConnection() throws IOException {
        failSend(first);

        connections.connect(1L, 17L);

        assertThat(gauge.value()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"notification", "heartbeat"})
    void failedSendRemovesOnlyTheFailedConnectionBeforeCompletionCallback(String operation) throws IOException {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        failSend(first);

        if (operation.equals("heartbeat")) {
            connections.heartbeat();
        } else {
            connections.notifyChanged(1L, 17L);
        }

        assertThat(gauge.value()).isEqualTo(1);
        closeCallback(first, "completion").run();
        assertThat(gauge.value()).isEqualTo(1);
    }

    @Test
    void shutdownClearsConnectionsWithoutWaitingForContainerCallbacks() {
        connections.connect(1L, 17L);
        connections.connect(2L, 17L);

        connections.shutdown();

        assertThat(gauge.value()).isZero();
        closeCallback(first, "completion").run();
        assertThat(gauge.value()).isZero();
    }

    private void failSend(SseEmitter emitter) throws IOException {
        doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    private Runnable closeCallback(SseEmitter emitter, String reason) {
        if (reason.equals("error")) {
            ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
            verify(emitter).onError(error.capture());
            return () -> error.getValue().accept(new IOException("disconnected"));
        }
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        if (reason.equals("timeout")) {
            verify(emitter).onTimeout(callback.capture());
        } else {
            verify(emitter).onCompletion(callback.capture());
        }
        return callback.getValue();
    }
}
