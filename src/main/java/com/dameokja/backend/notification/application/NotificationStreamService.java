package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.infrastructure.NotificationSseConnections;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class NotificationStreamService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final NotificationSseConnections connections;

    public SseEmitter connect(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        return connections.connect(userId, refrigeratorId);
    }
}
