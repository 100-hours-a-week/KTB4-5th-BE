package com.dameokja.backend.notification.infrastructure;

import static com.dameokja.backend.analysis.domain.AnalysisStatus.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.notification.domain.AnalysisFinished;
import com.dameokja.backend.notification.domain.AnalysisImageFinished;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class AnalysisFinishedRedisPubSubTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final NotificationSseConnections connections = mock(NotificationSseConnections.class);
    private final JsonMapper mapper = new JsonMapper();
    private final AnalysisFinishedRedisPubSub pubSub = new AnalysisFinishedRedisPubSub(redis, mapper, connections);
    private final AnalysisFinished finished = AnalysisFinished.of(1L, "analysis-id", COMPLETED, 3, 3, 0, OffsetDateTime.parse("2026-10-22T19:00:00+09:00"));

    @ParameterizedTest
    @NullSource
    @EnumSource(value = AnalysisStatus.class, names = {"QUEUED", "PROCESSING", "PARTIALLY_COMPLETED"})
    void rejectsNonterminalAndInvalidImageStatuses(AnalysisStatus status) {
        assertThatThrownBy(() -> new AnalysisImageFinished.Payload("analysis-id", "image-key", status, 3, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        if (status != PARTIALLY_COMPLETED) {
            assertThatThrownBy(() -> AnalysisFinished.of(1L, "analysis-id", status, 3, 0, 0, OffsetDateTime.parse("2026-10-22T19:00:00+09:00"))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"COMPLETED", "FAILED"})
    void publishesAndRoutesImageCompletion(AnalysisStatus status) {
        AnalysisImageFinished imageFinished = new AnalysisImageFinished(1L, new AnalysisImageFinished.Payload("analysis-id", "image-key", status, 3, 1, 1, null));
        pubSub.publish(imageFinished);
        verify(redis).convertAndSend(AnalysisFinishedRedisPubSub.IMAGE_FINISHED_CHANNEL, mapper.writeValueAsString(imageFinished));
        receive(AnalysisFinishedRedisPubSub.IMAGE_FINISHED_CHANNEL, mapper.writeValueAsString(imageFinished));
        verify(connections).notifyAnalysisImageFinished(imageFinished);
    }

    @Test
    void publishesAndRoutesUserScopedCompletion() {
        pubSub.publish(finished);
        verify(redis).convertAndSend(AnalysisFinishedRedisPubSub.CHANNEL, mapper.writeValueAsString(finished));
        receive(AnalysisFinishedRedisPubSub.CHANNEL, mapper.writeValueAsString(finished));
        verify(connections).notifyAnalysisFinished(finished);
    }

    @Test
    void ignoresMalformedOrIncompleteMessages() {
        for (String body : new String[] {"not-json", "null", "{}", "{\"userId\":1,\"analysisId\":\"id\"}",
                mapper.writeValueAsString(finished).replace("\"userId\":1", "\"userId\":-1"),
                mapper.writeValueAsString(finished).replace("analysis-id", " ")}) {
            receive(AnalysisFinishedRedisPubSub.CHANNEL, body);
        }
        receive(AnalysisFinishedRedisPubSub.IMAGE_FINISHED_CHANNEL, "{}");
        verifyNoInteractions(connections);
    }

    @Test
    void publicationFailureIsExposedForCompletionRetry() {
        doThrow(new IllegalStateException("redis unavailable")).when(redis).convertAndSend(anyString(), anyString());
        assertThatThrownBy(() -> pubSub.publish(finished)).isInstanceOf(IllegalStateException.class);
    }

    private void receive(String channel, String body) {
        pubSub.onMessage(new DefaultMessage(channel.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);
    }
}
