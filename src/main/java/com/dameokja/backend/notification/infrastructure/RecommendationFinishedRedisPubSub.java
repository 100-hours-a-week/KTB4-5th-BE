package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.recipe.domain.RecommendationGenerationFinished;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationFinishedRedisPubSub implements MessageListener {
    static final String CHANNEL = "fridgely:recommendations:finished";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final NotificationSseConnections connections;

    @TransactionalEventListener
    public void afterCommit(RecommendationGenerationFinished finished) {
        try { redis.convertAndSend(CHANNEL, mapper.writeValueAsString(finished)); }
        catch (RuntimeException exception) {
            // 결과 저장은 끝났으므로 신호 전송 실패가 완료 처리를 실패시키지 않는다.
            log.warn("추천 종료 SSE 신호 발행 실패. userId={}, refrigeratorId={}", finished.userId(), finished.refrigeratorId(), exception);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RecommendationGenerationFinished finished = mapper.readValue(message.getBody(), RecommendationGenerationFinished.class);
            if (finished != null) { connections.notifyRecommendationFinished(finished); }
        } catch (RuntimeException exception) { log.warn("추천 종료 SSE 신호 처리 실패", exception); }
    }
}
