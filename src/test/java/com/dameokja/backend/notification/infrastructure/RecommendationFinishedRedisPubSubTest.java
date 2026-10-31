package com.dameokja.backend.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.dameokja.backend.recipe.domain.RecommendationGenerationFinished;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationStatus;
import com.dameokja.backend.recipe.domain.RecommendationState.GenerationTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class RecommendationFinishedRedisPubSubTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final NotificationSseConnections connections = mock(NotificationSseConnections.class);
    private final RecommendationFinishedRedisPubSub pubSub = new RecommendationFinishedRedisPubSub(redis, JsonMapper.builder().findAndAddModules().build(), connections);

    @Test
    void redisFailureAfterCommitDoesNotMakeCompletionFail() {
        doThrow(new DataAccessResourceFailureException("Redis unavailable")).when(redis).convertAndSend(eq(RecommendationFinishedRedisPubSub.CHANNEL), anyString());
        RecommendationGenerationFinished finished = new RecommendationGenerationFinished(1L, 17L, GenerationTrigger.USER, GenerationStatus.COMPLETED);
        assertThatCode(() -> pubSub.afterCommit(finished)).doesNotThrowAnyException();
    }
}
