package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.NotificationChanged;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class NotificationSseConnections {
    private static final long CONNECTION_TIMEOUT_MS = 300_000L;
    private final Map<Long, UserSseConnections> connections = new ConcurrentHashMap<>();
    private final Supplier<SseEmitter> emitterFactory;

    public NotificationSseConnections() {
        this(() -> new SseEmitter(CONNECTION_TIMEOUT_MS));
    }

    NotificationSseConnections(Supplier<SseEmitter> emitterFactory) {
        this.emitterFactory = emitterFactory;
    }

    /**
     * 접근 권한을 확인한 사용자·냉장고의 SSE 연결을 등록하고 최초 연결 이벤트를 전송합니다.
     * 호출 측에서 인증과 냉장고 접근 권한을 확인한 후 호출해야 합니다.
     * @param userId 인증된 사용자의 ID
     * @param refrigeratorId 접근 권한을 확인한 냉장고의 ID
     * @return Spring MVC가 HTTP SSE 응답에 사용할 emitter
     */
    public SseEmitter connect(Long userId, Long refrigeratorId) {
        SseEmitter emitter = emitterFactory.get();
        NotificationSseConnection connection = new NotificationSseConnection(userId, refrigeratorId, emitter);
        connection.onClose(() -> remove(userId, connection));
        register(userId, connection);
        try {
            connection.sendConnected();
        } catch (IOException | IllegalStateException exception) {
            remove(userId, connection);
            connection.completeWithError(exception);
        }
        return emitter;
    }

    /**
     * 사용자·냉장고에 해당하는 연결에 알림 갱신 신호를 전달하고 빈 사용자 그룹을 제거합니다.
     * @param userId 알림함이 변경된 사용자의 ID
     * @param refrigeratorId 알림함이 변경된 냉장고의 ID
     */
    public void notifyChanged(Long userId, Long refrigeratorId) {
        UserSseConnections group = connections.get(userId);
        if (group == null) {
            return;
        }
        group.notifyChanged(new NotificationChanged(userId, refrigeratorId));
        removeEmptyGroup(userId);
    }

    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        connections.forEach((userId, group) -> {
            group.heartbeat();
            removeEmptyGroup(userId);
        });
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(UserSseConnections::closeAll);
        connections.clear();
    }

    private void register(Long userId, NotificationSseConnection connection) {
        connections.compute(userId, (id, existing) -> {
            UserSseConnections group = existing == null ? new UserSseConnections() : existing;
            group.add(connection);
            return group;
        });
    }

    private void remove(Long userId, NotificationSseConnection connection) {
        // 등록과 마지막 연결 제거를 같은 사용자 키에서 원자적으로 처리한다.
        connections.computeIfPresent(userId, (id, group) -> {
            group.remove(connection);
            return group.isEmpty() ? null : group;
        });
    }

    private void removeEmptyGroup(Long userId) {
        connections.computeIfPresent(userId, (id, group) -> group.isEmpty() ? null : group);
    }
}
