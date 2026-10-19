package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dameokja.backend.notification.domain.NotificationChanged;
import com.dameokja.backend.refrigerator.domain.MemberKicked;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class NotificationRedisPubSubTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final NotificationSseConnections connections = mock(NotificationSseConnections.class);
    private final NotificationRedisPubSub notificationPubSub = new NotificationRedisPubSub(redis, new JsonMapper(), connections);

    @Test
    void publishesUserScopedSignalAndRoutesReceivedSignal() {
        notificationPubSub.afterCommit(new NotificationChanged(1L, 17L));
        verify(redis).convertAndSend(NotificationRedisPubSub.CHANNEL, "{\"userId\":1,\"refrigeratorId\":17}");

        receive("{\"userId\":1,\"refrigeratorId\":17}");

        verify(connections).notifyChanged(1L, 17L);
    }

    @Test
    void kickedUsesItsOwnChannelAndRejectsInvalidRoutingData() {
        notificationPubSub.afterCommit(new MemberKicked(1L, 17L));
        verify(redis).convertAndSend(NotificationRedisPubSub.KICKED_CHANNEL, "{\"userId\":1,\"refrigeratorId\":17}");
        for (String body : new String[] {"null", "not-json", "{\"userId\":1}", "{\"userId\":1,\"refrigeratorId\":0}"}) {
            receiveKicked(body);
        }
        verifyNoInteractions(connections);
        receiveKicked("{\"userId\":1,\"refrigeratorId\":17}");
        verify(connections).notifyKicked(new MemberKicked(1L, 17L));
        verifyNoMoreInteractions(connections);
    }

    @Test
    void kickedPublicationFailureDoesNotFailAnAlreadyCommittedRemoval() {
        doThrow(new IllegalStateException("redis unavailable")).when(redis).convertAndSend(anyString(), anyString());
        assertThatCode(() -> notificationPubSub.afterCommit(new MemberKicked(1L, 17L))).doesNotThrowAnyException();
    }

    private void receiveKicked(String body) {
        notificationPubSub.onMessage(new DefaultMessage(NotificationRedisPubSub.KICKED_CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);
    }

    @Test
    void ignoresMalformedAndIncompleteMessages() {
        receive("not-json");
        receive("{\"userId\":1}");
        receive("{\"userId\":-1,\"refrigeratorId\":17}");
        verifyNoInteractions(connections);
    }

    @Test
    void publicationFailureDoesNotFailAnAlreadyCommittedWrite() {
        doThrow(new IllegalStateException("redis unavailable")).when(redis).convertAndSend(anyString(), anyString());
        assertThatCode(() -> notificationPubSub.afterCommit(new NotificationChanged(1L, 17L))).doesNotThrowAnyException();
    }

    private void receive(String body) {
        notificationPubSub.onMessage(new DefaultMessage(NotificationRedisPubSub.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);
    }
}
