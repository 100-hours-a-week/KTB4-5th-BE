package com.dameokja.backend.push.application;

import com.dameokja.backend.global.util.BusinessTime;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PushNotificationCreationService {
    // 8시 알림이 12시를 넘겨 도착하면 이미 늦은 정보이므로 서울 기준 당일 정오까지만 보낸다.
    public static final LocalTime SEND_DEADLINE = LocalTime.NOON;

    private final PushNotificationRepository pushNotificationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createExpirationJobs(List<Long> notificationIds, LocalDate businessDate) {
        LocalDateTime now = BusinessTime.now(clock);
        List<PushInboxTarget> targets = pushNotificationRepository.findInboxPushTargetsByNotificationIds(notificationIds);
        saveJobs(targets, now, businessDate.atTime(SEND_DEADLINE));
    }

    // 전체 조회 부하 실험에서 기존 단계의 측정 조건을 유지한다.
    @Transactional
    public int createExpirationJobs() {
        LocalDateTime now = BusinessTime.now(clock);
        LocalDate today = now.toLocalDate();
        List<PushInboxTarget> targets = pushNotificationRepository.findInboxPushTargets(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        saveJobs(targets, now, today.atTime(SEND_DEADLINE));
        return targets.size();
    }

    private void saveJobs(List<PushInboxTarget> targets, LocalDateTime now, LocalDateTime expiresAt) {
        for (PushInboxTarget target : targets) {
            String payload = objectMapper.writeValueAsString(PushPayload.from(target.notification()));
            pushNotificationRepository.save(PushNotification.inbox(
                    target.notification(), target.device().getUser(), target.device(), payload, now, expiresAt));
        }
    }
}
