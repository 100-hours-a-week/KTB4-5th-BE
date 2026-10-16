package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.AnalysisFinished;
import com.dameokja.backend.notification.domain.AnalysisImageFinished;
import com.dameokja.backend.notification.domain.NotificationChanged;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자·냉장고에 연결된 SSE 한 건의 전달 대상, 이벤트 구성과 종료 콜백을 책임진다.
 * 연결 등록 전 접근 권한 검증은 호출 측 서비스가 수행한다.
 */
@RequiredArgsConstructor
@Slf4j
final class NotificationSseConnection {
    private static final long RECONNECT_DELAY_MS = 3_000L;
    private final Long userId;
    private final Long refrigeratorId;
    private final SseEmitter emitter;
    private final NotificationConnectionPresence presence;
    private final String connectionId = UUID.randomUUID().toString();
    private boolean closed;

    /** 사용자와 냉장고가 모두 일치하는 사건인지 판단한다. DB 접근 권한을 재검증하지는 않는다. */
    public boolean accepts(NotificationChanged changed) {
        return userId.equals(changed.userId()) && refrigeratorId.equals(changed.refrigeratorId());
    }

    /**
     * 완료·타임아웃·오류 시 목록에서 제거하도록 콜백을 등록한다.
     * 콜백은 여러 종료 경로에서 실행될 수 있으므로 반복 호출에도 안전해야 한다.
     */
    public void onClose(Runnable cleanup) {
        Runnable close = () -> {
            closePresence();
            cleanup.run();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(() -> {
            close.run();
            emitter.complete();
        });
        emitter.onError(error -> close.run());
    }

    /**
     * 프론트가 최초 연결·재연결 후 최신 상태를 조회하도록 connected 이벤트를 보낸다.
     *
     * @throws IOException 전송 실패 시 호출자가 연결을 정리하도록 전달한다.
     */
    public synchronized void sendConnected() throws IOException {
        if (closed) {
            throw new IllegalStateException("SSE 연결이 이미 종료되었습니다.");
        }
        presence.refresh(userId, connectionId);
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name("connected").reconnectTime(RECONNECT_DELAY_MS).data("{}");
        emitter.send(event);
    }

    /**
     * 자신의 사용자·냉장고에 해당하는 사건만 notifications-changed 이벤트로 전송한다.
     * 대상이 다른 사건은 전송하지 않는다.
     *
     * @throws IOException 전송 실패 시 사용자 연결 그룹이 연결을 정리하도록 전달한다.
     */
    public void sendChanged(NotificationChanged changed) throws IOException {
        if (!accepts(changed)) {
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name("notifications-changed")
                .data(Map.of("refrigeratorId", changed.refrigeratorId().toString()));
        emitter.send(event);
    }

    public void sendAnalysisImageFinished(AnalysisImageFinished imageFinished) throws IOException {
        if (!userId.equals(imageFinished.userId())) {
            return;
        }
        emitter.send(SseEmitter.event().name("image-analysis.image-finished").data(imageFinished.payload()));
    }

    public void sendAnalysisFinished(AnalysisFinished finished) throws IOException {
        if (!userId.equals(finished.userId())) {
            return;
        }
        emitter.send(SseEmitter.event().name("image-analysis.finished").data(finished.payload()));
    }

    /**
     * 목록 재조회를 유발하지 않는 heartbeat 주석을 전송한다.
     *
     * @throws IOException 전송 실패 시 사용자 연결 그룹이 연결을 정리하도록 전달한다.
     */
    public synchronized void heartbeat() throws IOException {
        if (closed) {
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event().comment("heartbeat");
        emitter.send(event);
        if (!closed) {
            presence.refresh(userId, connectionId);
        }
    }

    /** emitter를 완료 처리한다. 등록 목록 제거는 종료 콜백과 상위 관리자가 담당한다. */
    public void complete() {
        closePresence();
        emitter.complete();
    }

    /** 호출자가 등록 목록에서 제거한 실패 연결을 오류로 완료 처리한다. */
    public void completeWithError(Throwable error) {
        closePresence();
        emitter.completeWithError(error);
    }

    private synchronized void closePresence() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            presence.remove(userId, connectionId);
        } catch (RuntimeException exception) {
            // 종료 정리에 실패해도 lease TTL로 다른 서버의 온라인 상태가 만료된다.
            log.warn("SSE 연결 상태 제거 실패: userId={}", userId, exception);
        }
    }
}
