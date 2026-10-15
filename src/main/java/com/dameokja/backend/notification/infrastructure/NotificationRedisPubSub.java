package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.NotificationChanged;
import java.nio.charset.StandardCharsets;
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
public class NotificationRedisPubSub implements MessageListener {
    static final String CHANNEL = "fridgely:notifications:changed";
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final NotificationSseConnections connections;

    @TransactionalEventListener
    public void afterCommit(NotificationChanged changed) {
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(changed));
        } catch (RuntimeException exception) {
            // DB 커밋은 끝났으므로 Redis 장애를 쓰기 실패로 반환하지 않는다.
            log.warn("알림 갱신 신호 발행 실패", exception);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            NotificationChanged changed = mapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), NotificationChanged.class);
            if (changed == null) {
                log.warn("알림 갱신 신호 형식 오류");
                return;
            }
            connections.notifyChanged(changed.userId(), changed.refrigeratorId());
        } catch (RuntimeException exception) {
            log.warn("알림 갱신 신호 처리 실패", exception);
        }
    }
}
