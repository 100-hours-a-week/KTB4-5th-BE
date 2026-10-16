package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(RedisTestSupport.class)
class NotificationConnectionPresenceTest {
    private final StringRedisTemplate redis = RedisTestSupport.redisTemplate();
    private final NotificationConnectionPresence firstServer = new NotificationConnectionPresence(redis);
    private final NotificationConnectionPresence secondServer = new NotificationConnectionPresence(redis);

    @Test
    void serversSharePresenceAndClosingOneTabKeepsOtherTabsOnline() {
        firstServer.refresh(1L, "first");
        secondServer.refresh(1L, "second");
        assertThat(stream(secondServer).isConnected(1L)).isTrue();
        assertThat(stream(secondServer).isConnected(2L)).isFalse();
        firstServer.remove(1L, "first");
        assertThat(firstServer.isConnected(1L)).isTrue();
        secondServer.remove(1L, "second");
        assertThat(firstServer.isConnected(1L)).isFalse();
        assertThat(redis.hasKey(key(1L))).isFalse();
    }

    @Test
    void refreshPrunesDeadServersWithoutExpiringAnotherLiveConnection() {
        firstServer.refresh(1L, "dead-server");
        redis.opsForZSet().add(key(1L), "dead-server", 0);
        secondServer.refresh(1L, "live-server");
        firstServer.remove(1L, "dead-server");
        assertThat(firstServer.isConnected(1L)).isTrue();
        assertThat(redis.opsForZSet().range(key(1L), 0, -1)).containsExactly("live-server");
        assertThat(redis.getExpire(key(1L))).isBetween(1L, 75L);
    }

    @Test
    void expiredLeasesAreIgnoredEvenWhenTheUserKeyStillExists() {
        firstServer.refresh(1L, "stale");
        redis.opsForZSet().add(key(1L), "stale", 0);
        assertThat(secondServer.isConnected(1L)).isFalse();
        assertThat(redis.hasKey(key(1L))).isFalse();
    }

    @Test
    void aDeadServersKeyExpiresWithoutAnotherRequest() {
        firstServer.refresh(1L, "stale");
        redis.expire(key(1L), Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(redis.hasKey(key(1L))).isFalse());
        assertThat(secondServer.isConnected(1L)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"completion", "timeout", "error", "initial-send", "heartbeat", "shutdown"})
    void everyTerminationPathRemovesPresence(String termination) throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnections connections = new NotificationSseConnections(() -> emitter, firstServer);
        if (termination.equals("initial-send")) {
            doThrow(new IOException("closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }
        connections.connect(1L, 17L);
        switch (termination) {
            case "completion" -> callback(emitter, false).run();
            case "timeout" -> callback(emitter, true).run();
            case "error" -> {
                ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
                verify(emitter).onError(error.capture());
                error.getValue().accept(new IOException("closed"));
            }
            case "heartbeat" -> {
                doThrow(new IOException("closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
                connections.heartbeat();
            }
            case "shutdown" -> connections.shutdown();
            default -> { }
        }
        connections.heartbeat();
        assertThat(secondServer.isConnected(1L)).isFalse();
    }

    @Test
    void completionDuringHeartbeatCannotResurrectTheConnection() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnections connections = new NotificationSseConnections(() -> emitter, firstServer);
        connections.connect(1L, 17L);
        doAnswer(invocation -> {
            callback(emitter, false).run();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        connections.heartbeat();
        assertThat(secondServer.isConnected(1L)).isFalse();
    }

    @Test
    void concurrentCloseWaitsForHeartbeatAndRemovesItsRefreshedLease() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnections connections = new NotificationSseConnections(() -> emitter, firstServer);
        connections.connect(1L, 17L);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try (java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<?> heartbeat = executor.submit(connections::heartbeat);
            assertThat(sending.await(2, TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<?> close = executor.submit(callback(emitter, false));
            release.countDown();
            heartbeat.get(2, TimeUnit.SECONDS);
            close.get(2, TimeUnit.SECONDS);
        }
        connections.heartbeat();
        assertThat(secondServer.isConnected(1L)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationAndHeartbeatRedisFailuresCloseTheConnection(boolean heartbeat) {
        NotificationConnectionPresence failing = spy(firstServer);
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnections connections = new NotificationSseConnections(() -> emitter, failing);
        if (heartbeat) {
            connections.connect(1L, 17L);
        }
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
                .when(failing).refresh(any(), any());
        if (heartbeat) {
            connections.heartbeat();
        } else {
            connections.connect(1L, 17L);
        }
        assertThat(secondServer.isConnected(1L)).isFalse();
        verify(emitter).completeWithError(any(org.springframework.data.redis.RedisConnectionFailureException.class));
    }

    @Test
    void removalFailureFallsBackToTtlWithoutHeartbeatResurrection() {
        NotificationConnectionPresence failing = spy(firstServer);
        SseEmitter emitter = mock(SseEmitter.class);
        NotificationSseConnections connections = new NotificationSseConnections(() -> emitter, failing);
        connections.connect(1L, 17L);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
                .when(failing).remove(any(), any());
        callback(emitter, false).run();
        connections.heartbeat();
        verify(failing).refresh(any(), any());
        assertThat(redis.getExpire(key(1L))).isBetween(1L, 75L);
        redis.expire(key(1L), Duration.ofMillis(1));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(secondServer.isConnected(1L)).isFalse());
    }

    @Test
    void terminationBeforeLocalRegistrationDoesNotLeaveADeadConnection() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(emitter).onCompletion(any());
        NotificationSseConnections connections = new NotificationSseConnections(() -> emitter, firstServer);
        connections.connect(1L, 17L);
        connections.heartbeat();
        assertThat(secondServer.isConnected(1L)).isFalse();
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void redisReadFailureIsNotTreatedAsOffline() {
        StringRedisTemplate failing = mock(StringRedisTemplate.class);
        when(failing.execute(any(org.springframework.data.redis.core.script.RedisScript.class), any(java.util.List.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
        NotificationStreamService service = stream(new NotificationConnectionPresence(failing));
        assertThatThrownBy(() -> service.isConnected(1L)).isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
    }

    private Runnable callback(SseEmitter emitter, boolean timeout) {
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        if (timeout) {
            verify(emitter).onTimeout(callback.capture());
        } else {
            verify(emitter).onCompletion(callback.capture());
        }
        return callback.getValue();
    }

    private NotificationStreamService stream(NotificationConnectionPresence presence) {
        return new NotificationStreamService(mock(RefrigeratorAccessService.class), mock(NotificationSseConnections.class), presence);
    }

    private String key(Long userId) { return "fridgely:notifications:connections:" + userId; }
}
