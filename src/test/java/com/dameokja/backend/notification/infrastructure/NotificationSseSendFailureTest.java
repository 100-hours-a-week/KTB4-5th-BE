package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseSendFailureTest {
    private final SseEmitter failed = mock(SseEmitter.class);
    private final SseEmitter healthy = mock(SseEmitter.class);
    private final NotificationConnectionPresence presence = mock(NotificationConnectionPresence.class);
    private final ArrayDeque<SseEmitter> emitters = new ArrayDeque<>(List.of(failed, healthy));
    private final NotificationSseConnections connections = new NotificationSseConnections(emitters::remove, presence);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ioFailureRemovesOnlyTheFailedConnectionWithoutCompletingTheEmitterAgain(boolean heartbeat) throws Exception {
        if (heartbeat) {
            connections.connect(1L, 17L);
        }
        doThrow(new AsyncRequestNotUsableException("ServletResponse failed to flushBuffer", new IOException("Broken pipe")))
                .when(failed).send(any(SseEmitter.SseEventBuilder.class));
        if (heartbeat) {
            connections.heartbeat();
        } else {
            connections.connect(1L, 17L);
        }
        connections.connect(2L, 17L);
        connections.heartbeat();
        connections.heartbeat();

        assertThat(connections.activeConnectionCount()).isEqualTo(1);
        verify(presence).remove(eq(1L), any());
        verify(failed, times(heartbeat ? 2 : 1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(failed, never()).complete();
        verify(failed, never()).completeWithError(any());
        verify(healthy, times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void redisFailureStillCompletesTheEmitterWithAnError(boolean heartbeat) {
        if (heartbeat) {
            connections.connect(1L, 17L);
        }
        var failure = new RedisConnectionFailureException("Redis unavailable");
        doThrow(failure).when(presence).refresh(eq(1L), any());
        if (heartbeat) {
            connections.heartbeat();
        } else {
            connections.connect(1L, 17L);
        }

        assertThat(connections.activeConnectionCount()).isZero();
        verify(presence).remove(eq(1L), any());
        verify(failed).completeWithError(failure);
    }
}
