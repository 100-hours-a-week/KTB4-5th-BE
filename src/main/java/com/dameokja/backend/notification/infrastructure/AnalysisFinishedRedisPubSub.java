package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.AnalysisFinished;
import com.dameokja.backend.notification.domain.AnalysisImageFinished;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisFinishedRedisPubSub implements MessageListener {
    static final String CHANNEL = "fridgely:analysis:finished";
    static final String IMAGE_FINISHED_CHANNEL = "fridgely:analysis:image-finished";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final NotificationSseConnections connections;

    // 분석 결과는 Redis에 저장되므로 DB 트랜잭션 이벤트를 기다리지 않는다.
    public void publish(AnalysisFinished finished) {
        redis.convertAndSend(CHANNEL, mapper.writeValueAsString(finished));
    }

    public void publish(AnalysisImageFinished imageFinished) {
        redis.convertAndSend(IMAGE_FINISHED_CHANNEL, mapper.writeValueAsString(imageFinished));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            if (IMAGE_FINISHED_CHANNEL.equals(new String(message.getChannel(), StandardCharsets.UTF_8))) {
                AnalysisImageFinished imageFinished = mapper.readValue(message.getBody(), AnalysisImageFinished.class);
                if (imageFinished != null) {
                    connections.notifyAnalysisImageFinished(imageFinished);
                }
                return;
            }
            AnalysisFinished finished = mapper.readValue(message.getBody(), AnalysisFinished.class);
            if (finished != null) {
                connections.notifyAnalysisFinished(finished);
            }
        } catch (RuntimeException exception) {
            log.warn("분석 SSE 신호 처리 실패", exception);
        }
    }
}
