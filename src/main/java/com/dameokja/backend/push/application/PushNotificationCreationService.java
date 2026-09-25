package com.dameokja.backend.push.application;

import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.infrastructure.PushInboxTarget;
import com.dameokja.backend.push.infrastructure.PushNotificationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 발송 1단계: 4시에 생성된 만료·임박 알림을 읽어 구독마다 PENDING 발송 작업을 만든다. 전송은 하지 않는다.
@Service
@RequiredArgsConstructor
public class PushNotificationCreationService {
    // 8시 알림이 12시를 넘겨 도착하면 이미 늦은 정보이므로 당일 정오까지만 보낸다.
    private static final LocalTime SEND_DEADLINE = LocalTime.NOON;

    private final PushNotificationRepository pushNotificationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public int createExpirationJobs() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        List<PushInboxTarget> targets = pushNotificationRepository.findExpirationPushTargets(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        LocalDateTime expiresAt = today.atTime(SEND_DEADLINE);
        for (PushInboxTarget target : targets) {
            String payload = objectMapper.writeValueAsString(PushPayload.from(target.notification()));
            pushNotificationRepository.save(PushNotification.inbox(
                    target.notification(), target.recipient(), target.device(), payload, now, expiresAt));
        }
        return targets.size();
    }
}
