package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseConnectionsTest {
    private final SseEmitter first = mock(SseEmitter.class);
    private final SseEmitter second = mock(SseEmitter.class);
    private final SseEmitter otherUser = mock(SseEmitter.class);
    private NotificationSseConnections connections;

    @BeforeEach
    void setup() {
        ArrayDeque<SseEmitter> emitters = new ArrayDeque<>();
        emitters.add(first);
        emitters.add(second);
        emitters.add(otherUser);
        connections = new NotificationSseConnections(emitters::remove);
    }

    @Test
    void sendsChangesToEveryConnectionOfOnlyTheRecipient() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        connections.connect(2L, 17L);

        connections.notifyChanged(1L, 17L);

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void sendsChangesOnlyToConnectionsForTheMatchingUserAndFridge() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 18L);
        connections.connect(2L, 17L);

        connections.notifyChanged(1L, 17L);

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void reconnectForAnotherFridgeDoesNotReceiveThePreviousFridgesChanges() throws Exception {
        connections.connect(1L, 17L);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(first).onCompletion(completion.capture());
        completion.getValue().run();
        connections.connect(1L, 18L);

        connections.notifyChanged(1L, 17L);
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
        connections.notifyChanged(1L, 18L);

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void completionAndTimeoutRemoveOnlyTheirOwnConnection() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(first).onCompletion(completion.capture());
        verify(second).onTimeout(timeout.capture());
        completion.getValue().run();

        connections.notifyChanged(1L, 17L);

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        timeout.getValue().run();
        connections.heartbeat();
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second).complete();
    }

    @Test
    void failedConnectionDoesNotBlockOtherConnectionsAndIsRemoved() throws Exception {
        connections.connect(1L, 17L);
        connections.connect(1L, 17L);
        doThrow(new IOException("disconnected")).when(first).send(any(SseEmitter.SseEventBuilder.class));

        connections.notifyChanged(1L, 17L);
        connections.heartbeat();

        verify(first, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(first).completeWithError(any(IOException.class));
        verify(second, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void errorCallbackRemovesConnectionAndReconnectReceivesReadyAndChanges() throws Exception {
        connections.connect(1L, 17L);
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        verify(first).onError(error.capture());
        error.getValue().accept(new IOException("closed"));
        connections.connect(1L, 17L);

        connections.notifyChanged(1L, 17L);

        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void initialSendFailureIsRemovedBeforeHeartbeat() throws Exception {
        doThrow(new IOException("closed")).when(first).send(any(SseEmitter.SseEventBuilder.class));
        connections.connect(1L, 17L);
        connections.heartbeat();
        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(first).completeWithError(any(IOException.class));
    }

    @Test
    void connectionImmediatelySendsReadyEventAndShutdownCompletesIt() throws Exception {
        assertThat(connections.connect(1L, 17L)).isSameAs(first);
        ArgumentCaptor<SseEmitter.SseEventBuilder> event = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(first).send(event.capture());
        assertThat(event.getValue().build()).anyMatch(part -> part.getData().toString().contains("connected"));

        connections.shutdown();

        verify(first).complete();
    }
}
