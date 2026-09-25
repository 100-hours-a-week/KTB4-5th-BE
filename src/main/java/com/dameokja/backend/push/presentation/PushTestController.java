package com.dameokja.backend.push.presentation;

import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.push.application.PushSendService;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 로컬 수동 검증용. push.test-api.enabled=true일 때만 등록되며 운영에서는 켜지 않는다.
@Hidden
@RestController
@RequestMapping("/api/v1/push-subscriptions")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "push.test-api.enabled", havingValue = "true")
public class PushTestController {
    // 수동 확인용이라 기기가 꺼져 있을 때 오래 보관할 필요가 없다.
    private static final Duration TEST_TTL = Duration.ofMinutes(5);

    private final PushSendService pushSendService;

    @PostMapping("/{subscriptionId}/test-sends")
    public Map<String, Boolean> send(@CurrentUserId Long userId, @PathVariable Long subscriptionId,
            @RequestBody String payloadJson) {
        return Map.of("sent", pushSendService.sendToOwnDevice(userId, subscriptionId, payloadJson, TEST_TTL));
    }
}
