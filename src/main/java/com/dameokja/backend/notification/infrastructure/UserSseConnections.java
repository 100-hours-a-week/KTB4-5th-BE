package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.NotificationChanged;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 사용자의 로컬 SSE 연결 집합과 여러 연결에 대한 전달·실패 정리를 책임진다.
 * 어느 사용자의 그룹인지는 {@link NotificationSseConnections}가 관리하며,
 * 개별 사건의 전달 대상 판단과 이벤트 구성은 {@link NotificationSseConnection}에 맡긴다.
 * 등록하는 연결이 같은 사용자에게 속하는지는 호출 측에서 보장해야 한다.
 */
final class UserSseConnections {
    private final Set<NotificationSseConnection> connections = ConcurrentHashMap.newKeySet();

    public void add(NotificationSseConnection connection) {
        connections.add(connection);
    }

    public void remove(NotificationSseConnection connection) {
        connections.remove(connection);
    }

    public boolean isEmpty() {
        return connections.isEmpty();
    }

    /**
     * 사용자·냉장고가 일치하는 연결에 알림 갱신 사건을 전달하고 실패한 연결을 정리합니다.
     * 대상 일치 여부는 각 연결이 판단하며, 한 연결의 전송 실패로 다른 연결의 전달을 중단하지 않습니다.
     * @param changed 전달 대상 사용자·냉장고를 나타내는 알림 갱신 사건
     */
    public void notifyChanged(NotificationChanged changed) {
        connections.forEach(connection -> {
            try {
                connection.sendChanged(changed);
            } catch (IOException | IllegalStateException exception) {
                removeFailedConnection(connection, exception);
            }
        });
    }

    public void heartbeat() {
        connections.forEach(connection -> {
            try {
                connection.heartbeat();
            } catch (IOException | IllegalStateException exception) {
                removeFailedConnection(connection, exception);
            }
        });
    }

    public void closeAll() {
        connections.forEach(NotificationSseConnection::complete);
        connections.clear();
    }

    private void removeFailedConnection(NotificationSseConnection connection, Exception exception) {
        remove(connection);
        connection.completeWithError(exception);
    }
}
