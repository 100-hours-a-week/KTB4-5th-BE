package com.dameokja.backend.notification.presentation;

import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.notification.application.NotificationStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class NotificationStreamController implements NotificationStreamApi {
    private final NotificationStreamService streams;

    @Override
    @GetMapping(value = "/api/v2/refrigerators/{refrigeratorId}/notifications/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> connect(@CurrentUserId Long userId, @PathVariable Long refrigeratorId) {
        SseEmitter emitter = streams.connect(userId, refrigeratorId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(emitter);
    }
}
